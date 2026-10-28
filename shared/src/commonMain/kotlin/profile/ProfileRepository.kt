package profile

import app.lidory.duckcount.ProfileLimits
import database.data.local.dao.UserDao
import database.data.mappers.toUserEntity
import database.domain.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import util.map
import vault.ProfileKeyVault
import vault.sealBound
import vault.unsealBound

/** Sealed in place of a field the app has no screen for yet. */
private val UNSET_FIELD = ByteArray(0)

/**
 * What each sealed field of a profile is bound to: whose profile it is, and which of its fields.
 *
 * Every field of one profile is sealed under the same key, so without the field's name they
 * would all be interchangeable: the server could serve the empty phone number where the name
 * was, and it would decrypt perfectly into a blank name, or later the bank details into it.
 *
 * Without the owner's UUID, a key would open its owner's profile whichever user it was served
 * as. A key does not always come from its owner: the creator of a group hands every member the
 * keys of the others, see `MemberProfileRepository`, and the server, which serves whatever it
 * holds under whichever UUID it is asked for, could then pass one member's profile off as
 * another's, the key that opens it being on this device all the same. Bound to its owner, a
 * profile opens as theirs and nobody else's: only they can store one under their UUID.
 */
private object Context {
    fun fullName(ownerUuid: String) = "profile:$ownerUuid:full_name"
    fun phoneNumber(ownerUuid: String) = "profile:$ownerUuid:phone_number"
    fun bankDetails(ownerUuid: String) = "profile:$ownerUuid:bank_details"
}

/**
 * User profiles as the server holds them: the authenticated user's own, and the ones their
 * contacts shared with them, or the members of their groups, see `MemberProfileRepository`.
 *
 * The key to the user's own comes from the vault, and is what a contact is handed to read
 * this profile. It changes when the user removes a contact, see [oweProfileKeyRotation], and
 * the server keeps a version of the profile under each of the last keys, so that whoever holds
 * an older one reads the profile as it was when that key was replaced, and nothing after, and
 * learns from the read that it is not the latest, see [readFullName].
 */
class ProfileRepository(
    private val profileStore: ProfileStore,
    private val profileKeyVault: ProfileKeyVault,
    private val userDao: UserDao,
) {
    /**
     * Serializes [uploadProfile], [rotateProfileKey] and [oweProfileKeyRotation], the only ones
     * to read or write the key a removal owes the profile, see
     * [ProfileKeyVault.getPendingProfileEncryptionKey]. An edit sealed under the key a rotation is
     * replacing, and saved once the rotation is done, would rewrite the version under the old
     * key, the one the contact just removed can still read.
     */
    private val mutex = Mutex()

    /**
     * Uploads the user profile on the server. Seals the fields before storing them on
     * the server, and only then it writes the user profile to the local database.
     *
     * Under the current key, so it rewrites the current version and leaves the older ones as
     * they were: whoever holds an older key does not see the edit. Unless a removal still owes
     * the key a rotation, see [oweProfileKeyRotation]: the contact removed holds the current key
     * until it has rotated, which a failure can put off for as long as it lasts. The edit is
     * then the rotation itself, sealed under the new key and uploaded before the vault takes it,
     * as [rotateProfileKey] does, and the version under the old key stays as it stood at the
     * removal. When that fails, the edit is refused rather than sealed where the contact removed
     * would read it.
     *
     * A full name longer than [ProfileLimits.MAX_FULL_NAME_LENGTH] is refused before anything is
     * sealed, see [ProfileError.FullNameTooLong]: the server would refuse what it seals to.
     */
    suspend fun uploadProfile(
        userUuid: String,
        username: String,
        fullName: String,
    ): EmptyResult<ProfileError> {
        if (fullName.length > ProfileLimits.MAX_FULL_NAME_LENGTH) return Result.Error(ProfileError.FullNameTooLong)

        val fields = ProfileFields(
            fullName = fullName.encodeToByteArray(),
            phoneNumber = UNSET_FIELD,
            bankDetails = UNSET_FIELD,
        )

        val upload = mutex.withLock {
            val currentKey = when (val outcome = profileKeyVault.getOrCreateProfileEncryptionKey()) {
                is Result.Success -> outcome.data
                is Result.Error -> return@withLock Result.Error(ProfileError.Vault(outcome.error))
            }

            when (val owedKey = getOwedKey(currentKey = currentKey)) {
                null -> save(userUuid = userUuid, key = currentKey, fields = fields)
                else -> takeOwedKey(userUuid = userUuid, owedKey = owedKey, fields = fields)
            }
        }

        if (upload is Result.Error) return upload

        userDao.insert(
            User(
                uuid = userUuid,
                username = username,
                fullName = fullName,
            ).toUserEntity(),
        )

        return Result.Success(Unit)
    }

    /**
     * Fetches the user's profile and writes it into the local database.
     */
    suspend fun restoreProfile(userUuid: String, username: String): EmptyResult<ProfileError> {
        val key = when (val outcome = profileKeyVault.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> outcome.data
            is Result.Error -> return Result.Error(ProfileError.Vault(outcome.error))
        }

        val fullName = when (val read = getFullName(userUuid = userUuid, profileEncryptionKey = key)) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(read.error)
        }

        userDao.insert(
            User(
                uuid = userUuid,
                username = username,
                fullName = fullName,
            ).toUserEntity(),
        )

        return Result.Success(Unit)
    }

    /**
     * Owes the key to the user's profile a rotation, for a removal the user makes, then runs
     * [remove], which tells the contact and writes that removal down, returning what it returns,
     * see [ContactRepository.remove][database.data.repository.ContactRepository.remove], or null
     * when the device locked before the key could be kept, with [remove] never run.
     *
     * The new key is minted here and kept on the device until the vault holds it, see
     * [ProfileKeyVault.setPendingProfileEncryptionKey], so the rotation is owed from the moment
     * of the removal, whenever it gets done: every edit made before then goes up under the new
     * key, never under the one the contact removed holds, see [uploadProfile], and so does every
     * attempt at the rotation itself, see [rotateProfileKey].
     *
     * A fresh one, whatever was pending: that one may already be the vault's, its write having
     * landed with the answer lost, and then handed to the very contact being removed, see
     * [ProfileKeyDelivery][contact.ProfileKeyDelivery]. Kept, the next rotation would find the
     * vault holding it and take the rotation for done, leaving them the current key. A version
     * uploaded under it whose vault write never landed is left unread, one per such removal at
     * most.
     *
     * Both under the one lock every rotation takes, the key kept first and the removal written
     * last. A rotation between the two would make the new key the vault's, to be handed to a
     * contact still written down as one, and their removal would then find nothing left owed.
     * The other way round, a process killed in between would leave a removal waiting for a
     * rotation with no key owed, which the rotation takes for one already done, see
     * [rotateProfileKey], and the contact removed would read every edit after. This way, it leaves
     * a key owed for no removal, which costs one rotation more, as does a [remove] that could not
     * tell the contact and so wrote nothing down.
     *
     * Kept before the contact is told, not once they have been: the key is kept with a write the
     * device has to be unlocked for, see [authenticationUtils.setPendingProfileKey], and by the
     * time the notice is out it may have locked. The contact drops the user as they read it, so a
     * write failing then would leave this device holding them as a contact for good, handing them
     * every key after.
     *
     * It may have locked before, too, the removal having waited on the lock for as long as a
     * rotation, an edit or another removal under way took, a few requests each on whatever network
     * there is. Nothing is done then, nobody told and nothing written down, and null says so, for
     * the user to be told and try again rather than have the write's failure end the app.
     */
    suspend fun <T : Any> oweProfileKeyRotation(remove: suspend () -> T): T? = mutex.withLock {
        try {
            profileKeyVault.setPendingProfileEncryptionKey(profileEncryptionKey = generateSymmetricEncryptionKey())
        } catch (exception: CancellationException) {
            // The caller going away, which the catch below would otherwise take for a locked
            // device, a CancellationException being an IllegalStateException.
            throw exception
        } catch (_: IllegalStateException) {
            return@withLock null
        }

        remove()
    }

    /**
     * Replaces the key to the user's profile with the one a removal owes it, see
     * [oweProfileKeyRotation], sealing the same fields under it, so that whoever holds the old
     * one, the contact the user removed, reads nothing the user writes from now on. They cannot
     * even ask the server for it: every read is gated on the hash of the key the version asked for
     * was sealed with.
     *
     * In an order that never puts a key in use that the server holds no profile under: the
     * profile as it stands is read under the current key, the very same fields are sealed under
     * the new one and uploaded under its hash, and only then does the vault take the new key.
     * Written first, it could end up the key every contact is handed while the server holds
     * nothing under it.
     *
     * The upload adds a version and leaves the one under the old key as it was, see
     * [ProfileStore.saveUserProfile], so the removed contact goes on reading the profile as it
     * stood at the removal, and so does every contact left until handed the new key, see
     * [ProfileKeyDelivery][contact.ProfileKeyDelivery].
     *
     * The new key is kept on the device from the removal on, and every attempt after a failure
     * uploads under that same key. A fresh one each time would add a version per attempt whose
     * upload landed and whose vault write did not, under a key nobody holds, and the server
     * keeping the newest ten, enough of them would prune the version the vault's key opens:
     * every contact reading it would find nothing, and this would fail for good, finding no
     * profile to read under the current key. An attempt that finds the vault already holding
     * the pending key is one whose vault write landed with its answer lost, or whose drop of the
     * key never reached the disk: the rotation is done, and only the pending key is left to
     * drop. One that finds no pending key has nothing left to do either: the rotation was done
     * since the removal, by an edit, see [uploadProfile], or by an attempt whose caller never
     * heard the end of it.
     */
    suspend fun rotateProfileKey(userUuid: String): EmptyResult<ProfileError> = mutex.withLock {
        val currentKey = when (val outcome = profileKeyVault.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> outcome.data
            is Result.Error -> return@withLock Result.Error(ProfileError.Vault(outcome.error))
        }

        val owedKey = getOwedKey(currentKey = currentKey) ?: return@withLock Result.Success(Unit)

        val current = when (val read = fetch(userUuid = userUuid, profileEncryptionKey = currentKey)) {
            is Result.Success -> read.data
            is Result.Error -> return@withLock read
        }

        val fields = ProfileFields.unseal(profile = current, key = currentKey, ownerUuid = userUuid)
            ?: return@withLock Result.Error(ProfileError.Crypto)

        takeOwedKey(userUuid = userUuid, owedKey = owedKey, fields = fields)
    }

    /**
     * The key a removal owes the profile, see [oweProfileKeyRotation], or null when none is owed:
     * none ever was, or the rotation is done. It is done when the pending key is [currentKey],
     * the vault's write of it having landed with the answer lost, or the drop that followed it
     * never having reached the disk, see [ProfileKeyVault.dropPendingProfileEncryptionKey], and
     * only the pending key is then left to drop.
     */
    private suspend fun getOwedKey(currentKey: ByteArray): ByteArray? {
        val pendingKey = profileKeyVault.getPendingProfileEncryptionKey() ?: return null

        if (!pendingKey.contentEquals(currentKey)) return pendingKey

        profileKeyVault.dropPendingProfileEncryptionKey()

        return null
    }

    /**
     * Uploads [fields] under [owedKey], the key a removal owes the profile, and only then has the
     * vault take it as the current key, after which the device no longer keeps it, see
     * [rotateProfileKey] on why in that order.
     */
    private suspend fun takeOwedKey(
        userUuid: String,
        owedKey: ByteArray,
        fields: ProfileFields,
    ): EmptyResult<ProfileError> {
        val upload = save(userUuid = userUuid, key = owedKey, fields = fields)

        if (upload is Result.Error) return upload

        val replacement = profileKeyVault.replaceProfileEncryptionKey(profileEncryptionKey = owedKey)

        if (replacement is Result.Error) return Result.Error(ProfileError.Vault(replacement.error))

        profileKeyVault.dropPendingProfileEncryptionKey()

        return Result.Success(Unit)
    }

    /**
     * The full name on the profile of [userUuid], which [profileEncryptionKey] seals, for a
     * caller with no use for whether it is their latest, see [readFullName].
     */
    suspend fun getFullName(
        userUuid: String,
        profileEncryptionKey: ByteArray,
    ): Result<String, ProfileError> =
        readFullName(userUuid = userUuid, profileEncryptionKey = profileEncryptionKey).map { it.fullName }

    /**
     * The full name on the profile of [userUuid], which [profileEncryptionKey] seals, and
     * whether the version it was read off is their latest.
     *
     * Reads anybody's profile, the user's own or a contact's, as long as the key is the
     * one its owner sealed it with: the version under it, which is the latest only for
     * whoever was handed the current key. Anybody else reads the profile as it stood when their
     * key was replaced, and learns from this that a newer one exists, nothing more, see
     * [ProfileKeyPull][contact.ProfileKeyPull].
     *
     * Only a profile [userUuid] sealed opens, see [Context]: one of somebody else's served under
     * their UUID is [ProfileError.Crypto], whatever key opens it.
     */
    suspend fun readFullName(
        userUuid: String,
        profileEncryptionKey: ByteArray,
    ): Result<FullNameRead, ProfileError> {
        val profile = when (val read = fetch(userUuid = userUuid, profileEncryptionKey = profileEncryptionKey)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val fullName = unsealBound(
            key = profileEncryptionKey,
            context = Context.fullName(ownerUuid = userUuid),
            content = profile.fullName,
        )?.decodeToString() ?: return Result.Error(ProfileError.Crypto)

        return Result.Success(FullNameRead(fullName = fullName, isLatest = profile.isLatest))
    }

    /** The version of the profile of [userUuid] that [profileEncryptionKey] seals. */
    private suspend fun fetch(
        userUuid: String,
        profileEncryptionKey: ByteArray,
    ): Result<ServedUserProfileDto, ProfileError> =
        when (
            val read = profileStore.getUserProfile(
                userUuid = userUuid,
                encryptionKeyPartialHash = profileEncryptionKeyPartialHash(profileEncryptionKey),
            )
        ) {
            is Result.Success -> read

            is Result.Error -> Result.Error(
                when (read.error) {
                    NetworkError.NOT_FOUND -> ProfileError.NotStored
                    else -> ProfileError.Network(read.error)
                },
            )
        }

    /** Seals [fields] under [key], bound to [userUuid], and uploads them as the user's version under that key. */
    private suspend fun save(
        userUuid: String,
        key: ByteArray,
        fields: ProfileFields,
    ): EmptyResult<ProfileError> {
        val profile = fields.seal(key = key, ownerUuid = userUuid) ?: return Result.Error(ProfileError.Crypto)

        val save = profileStore.saveUserProfile(
            userUuid = userUuid,
            encryptionKeyPartialHash = profileEncryptionKeyPartialHash(key),
            body = profile,
        )

        return when (save) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(ProfileError.Network(save.error))
        }
    }
}

/** The fields of a profile in the clear, each sealed bound to its owner and its own [Context]. */
private class ProfileFields(
    val fullName: ByteArray,
    val phoneNumber: ByteArray,
    val bankDetails: ByteArray,
) {
    /** These fields sealed under [key] as [ownerUuid]'s, or null when the key is the wrong size. */
    fun seal(key: ByteArray, ownerUuid: String): UserProfileDto? =
        UserProfileDto(
            fullName = sealBound(key = key, context = Context.fullName(ownerUuid), plaintext = fullName)
                ?: return null,
            phoneNumber = sealBound(key = key, context = Context.phoneNumber(ownerUuid), plaintext = phoneNumber)
                ?: return null,
            bankDetails = sealBound(key = key, context = Context.bankDetails(ownerUuid), plaintext = bankDetails)
                ?: return null,
        )

    companion object {
        /**
         * The fields of [profile], or null when any of them does not open with [key] as
         * [ownerUuid]'s.
         */
        fun unseal(profile: ServedUserProfileDto, key: ByteArray, ownerUuid: String): ProfileFields? =
            ProfileFields(
                fullName = unsealBound(key = key, context = Context.fullName(ownerUuid), content = profile.fullName)
                    ?: return null,
                phoneNumber = unsealBound(
                    key = key,
                    context = Context.phoneNumber(ownerUuid),
                    content = profile.phoneNumber,
                ) ?: return null,
                bankDetails = unsealBound(
                    key = key,
                    context = Context.bankDetails(ownerUuid),
                    content = profile.bankDetails,
                ) ?: return null,
            )
    }
}

/** The full name read off a version of somebody's profile, see [ProfileRepository.readFullName]. */
class FullNameRead(
    val fullName: String,

    /** Whether that version is their latest, see [ServedUserProfileDto.isLatest]. */
    val isLatest: Boolean,
)
