package database.data.repository

import authenticationUtils.clearContactsToRestore
import authenticationUtils.getAuthenticatedUserUuid
import authenticationUtils.hasContactsToRestore
import contact.ContactRemoval
import contact.ContactSyncError
import contact.RemoveContactError
import database.data.local.dao.ContactDao
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.dao.PendingProfileKeyDao
import database.data.local.entity.ContactEntity
import database.data.local.entity.DirectChannelEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.remote.DirectMessageApi
import database.data.remote.SendDirectMessageBody
import database.data.remote.SendDirectMessageError
import database.domain.User
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import message.DirectMessageEnvelope
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.encryptApplicationMessage
import mls.isRetryable
import profile.ProfileError
import profile.ProfileRepository
import push.WakeList
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultRepository
import mls.pinnedSignatureKeyFor
import mls.restorePin
import mls.withPeerLock
import vault.VaultContact

/**
 * The user's contacts: the people whose request they accepted, and the ones who accepted
 * theirs.
 *
 * The vault is where contacts are kept, since the server holds no record of them.
 * This device keeps a copy, and their names in [UserRepository], which is what the contact
 * list shows.
 *
 * Every change lands on the device first, and [backUp] carries it to the vault.
 *
 * Removing a contact also replaces the key to the user's own profile, which the one removed
 * holds, see [ContactEntity.rotatesProfileKey], and every contact left is handed the new one,
 * see [ProfileKeyDelivery][contact.ProfileKeyDelivery]. A contact's own key, handed over the
 * same way, can go missing on the way, and reading their profile is what tells, see
 * [resolveNames].
 */
class ContactRepository(
    private val contactDao: ContactDao,
    private val directChannelDao: DirectChannelDao,
    private val identityConflictDao: IdentityConflictDao,
    private val pendingExpenseKeyDao: PendingExpenseKeyDao,
    private val pendingProfileKeyDao: PendingProfileKeyDao,
    private val userRepository: UserRepository,
    private val vaultRepository: VaultRepository,
    private val profileRepository: ProfileRepository,
    private val directMessageApi: DirectMessageApi,
    private val mlsIdentityRepository: MlsIdentityRepository,
    private val wakeList: WakeList,
) {
    /**
     * Serializes [backUp]. The contacts are sealed whole, so two writes from this device
     * would conflict, and the second one would fail for nothing.
     */
    private val backUpMutex = Mutex()

    /**
     * Serializes [restore], which the message sync and the contact requests can all reach at
     * once right after a login. A second caller waits for the first one's contacts rather
     * than reading the vault again and pinning from a read of its own.
     */
    private val restoreMutex = Mutex()

    /** Whether [userUuid] is one of the user's contacts. */
    suspend fun isContact(userUuid: String): Boolean {
        return contactDao.get(userUuid = userUuid) != null
    }

    /**
     * Records [userUuid] as a contact on this device.
     *
     * [fullName], the name on their profile, is written along when it is already known,
     * and left to [resolveNames] otherwise. [handedProfileKeyHash] is the user's own key the
     * card that made them a contact carried, see [ContactEntity.handedProfileKeyHash]. The
     * vault only gets them with the next [backUp]. They may wake the user from here on, see
     * [WakeList]. One who still is a contact keeps the safety number the user verified with
     * them, see [ContactDao.add].
     *
     * The group the two of them talk through is recorded separately, see
     * [database.data.local.entity.DirectChannelEntity]: a channel outlives the contact, and
     * whoever knows which group it is records it, the acceptance that made them a contact,
     * the user's or theirs, and after that the sync that reads them in another group.
     */
    suspend fun add(
        userUuid: String,
        profileEncryptionKey: ByteArray,
        handedProfileKeyHash: String?,
        fullName: String?,
    ) {
        contactDao.add(
            userUuid = userUuid,
            profileEncryptionKey = profileEncryptionKey,
            handedProfileKeyHash = handedProfileKeyHash,
        )

        fullName?.let { saveName(userUuid = userUuid, fullName = it) }

        wakeList.rebuild()
    }

    /**
     * Acts on [senderUserUuid]'s notice that they removed the user from their contacts.
     *
     * The user's profile key stays as it is, see [ContactEntity.rotatesProfileKey]: it only
     * ever rotates on the user's own action.
     *
     * The client that deletes the groups the two of them shared is opened only if there is one,
     * see [forget], already open by then: the notice was just read with it.
     */
    internal suspend fun receiveRemoval(senderUserUuid: String) {
        forget(userUuid = senderUserUuid, client = null, rotatesProfileKey = false)
    }

    /**
     * Takes [profileEncryptionKey] as the new key to the profile of [senderUserUuid], from
     * their [ProfileKeyUpdate][contact.ProfileKeyUpdate], pushed as it rotated or answering
     * this device's request for it. The vault only gets it with the next [backUp].
     *
     * Only from a contact: anybody else is handing over the key to a profile this device has
     * no business reading, and a former contact one it no longer reads. The name shown stays
     * as it is, and [resolveNames] reads it with the new key whenever this device has none, or
     * was missing their current key, which it reads to find out whether this is the one.
     *
     * The very key this device holds is the contact saying it is their current one, so there
     * is nothing newer to ask them for, whatever the version it opens says: one of theirs can
     * be newer and under a key they never held, a rotation of theirs cut short by a log-out
     * having left it, see `ProfileRepository.rotateProfileKey`. Asked again, they would only
     * answer the same, every day.
     */
    internal suspend fun receiveProfileKey(senderUserUuid: String, profileEncryptionKey: ByteArray) {
        val contact = contactDao.get(userUuid = senderUserUuid) ?: return

        if (contact.profileEncryptionKey.contentEquals(profileEncryptionKey)) {
            pendingProfileKeyDao.delete(userUuid = senderUserUuid)

            return
        }

        contactDao.replaceProfileEncryptionKey(
            userUuid = senderUserUuid,
            profileEncryptionKey = profileEncryptionKey,
        )
    }

    /**
     * Removes the contact [userUuid], telling them first so their client removes the user
     * in turn.
     *
     * The notice travels in the group the request between the two of them created, so it
     * can only be sent while this device still holds it: a contact restored from the vault
     * after a log-out or a reinstall is removed without one, and keeps the user. Nothing is
     * removed while the notice has yet to go out, so a failure can simply be retried.
     *
     * Either way, they hold the key to the user's profile, which is owed a rotation from then on,
     * see [ProfileRepository.oweProfileKeyRotation], done by the next [backUp] before the vault
     * drops them, see [ContactEntity.rotatesProfileKey], or by an edit of the profile made first:
     * they then read the profile as it is now, and nothing the user writes after.
     *
     * The rotation is owed before the notice goes out, see
     * [ProfileRepository.oweProfileKeyRotation] on why, under a lock that waits for any rotation
     * of the key or edit of the profile under way and holds off any other until the removal is
     * written down. A device that locked during that wait cannot keep the key owed, and nothing
     * is done, see [RemoveContactError.DeviceLocked]. Not cancellable once the notice is out: the
     * contact drops the user as they read it, and nothing would ever tell this device to follow.
     */
    suspend fun remove(userUuid: String): EmptyResult<RemoveContactError> {
        // Not a contact any more, which is all removing them was for.
        contactDao.get(userUuid = userUuid) ?: return Result.Success(Unit)

        return profileRepository.oweProfileKeyRotation { tellAndForget(userUuid = userUuid) }
            ?: Result.Error(RemoveContactError.DeviceLocked)
    }

    /**
     * Tells the contact [userUuid] that the user removed them, when this device holds a group to
     * tell them in, then forgets them, for [remove], once the rotation is owed.
     */
    private suspend fun tellAndForget(userUuid: String): EmptyResult<RemoveContactError> {
        val mlsGroupId = directChannelDao.get(peerUuid = userUuid)?.mlsGroupId

        if (mlsGroupId == null) {
            forget(userUuid = userUuid, client = null, rotatesProfileKey = true)

            return Result.Success(Unit)
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(RemoveContactError.Identity(opened.error))
        }

        val notice = sendRemovalNotice(client = client, userUuid = userUuid, mlsGroupId = mlsGroupId)

        if (notice is Result.Error) return notice

        withContext(NonCancellable) {
            forget(userUuid = userUuid, client = client, rotatesProfileKey = true)
        }

        return Result.Success(Unit)
    }

    /**
     * Carries to the vault every change it has not taken yet, additions and removals alike.
     *
     * The MLS identity pinned for each contact goes along with them. Pins live in the local
     * database, which a log-out empties, and a device that came back without them would
     * accept whichever key package the server served next for each contact and pin that,
     * which is the substitution pinning exists to catch.
     *
     * The vault never takes a pin over the one it already holds for a contact, see
     * [ContactBook.with][vault.ContactBook.with]. A contact it pins somebody else for is
     * blocked instead, see [IdentityConflictEntity], and is not written again.
     *
     * A contact the user removed reaches the vault only once the key to the user's profile,
     * which they hold, has rotated, see [ContactEntity.rotatesProfileKey]: one rotation for
     * every such removal read, ahead of the write. When it fails, the rest is written all the
     * same, those removals wait on the device for the next attempt, and the failure is
     * reported.
     */
    suspend fun backUp(): EmptyResult<ContactSyncError> = backUpMutex.withLock {
        val pending = PendingBackUp(contacts = contactDao.getNotBackedUp())

        // Most calls find nothing to write, and a write costs two requests at least.
        if (pending.isEmpty()) return@withLock Result.Success(Unit)

        // Nothing is written without it: a contact marked backed up without the pin this
        // device holds for them would never be written again.
        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return@withLock Result.Error(ContactSyncError.Identity(opened.error))
        }

        val rotation = pending.rotating.takeIf { it.isNotEmpty() }?.let { rotateProfileKey(rotating = it) }

        val removed = pending.removals(isProfileKeyRotated = rotation !is Result.Error)

        // Reported once whatever does not wait for it is written.
        val failure = (rotation as? Result.Error)?.error

        if (pending.added.isEmpty() && removed.isEmpty()) {
            return@withLock failure?.let { Result.Error(it) } ?: Result.Success(Unit)
        }

        // Nor without the pins themselves, for the same reason: one that could not be read
        // is not the same as none.
        val contacts = try {
            pending.added.associate { contact ->
                contact.userUuid to VaultContact(
                    profileEncryptionKey = contact.profileEncryptionKey,
                    // Null for a contact this device has never reached over MLS, which
                    // leaves whatever another device recorded for them alone.
                    mlsSignaturePublicKey = client.pinnedSignatureKeyFor(peerUuid = contact.userUuid),
                    handedProfileKeyHash = contact.handedProfileKeyHash,
                    verifiedSafetyNumber = contact.verifiedSafetyNumber,
                )
            }
        } catch (_: MlsException) {
            return@withLock Result.Error(ContactSyncError.Storage)
        }

        val update = vaultRepository.updateContacts(
            added = contacts,
            removed = removed.map { it.userUuid },
        )

        val conflicting = when (update) {
            is Result.Success -> update.data
            is Result.Error -> return@withLock Result.Error(ContactSyncError.Vault(update.error))
        }

        conflicting.forEach { identityConflictDao.insert(IdentityConflictEntity(peerUuid = it)) }

        // A contact blocked must not wake the user a moment longer.
        if (conflicting.isNotEmpty()) wakeList.rebuild()

        // Both only touch rows still in the state they were read in, so a contact removed,
        // added back, handed a new key or marked verified during the write stays pending for the
        // next one.
        pending.added.forEach { contact ->
            contactDao.markBackedUp(
                userUuid = contact.userUuid,
                profileEncryptionKey = contact.profileEncryptionKey,
                handedProfileKeyHash = contact.handedProfileKeyHash,
                verifiedSafetyNumber = contact.verifiedSafetyNumber,
            )
        }

        contactDao.deleteRemoved(userUuids = removed.map { it.userUuid })

        failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Reads the profile of every contact this device has no name for, or was missing the current
     * key of and has a new one from, saving the name on it, see
     * [ContactDao.getDueForRead][database.data.local.dao.ContactDao.getDueForRead].
     *
     * What the read says of the key it was made with goes along: one that opens no version of
     * the profile, or one that is not the latest, is most often a key the contact replaced, the
     * new one having never reached this device, and the contact is asked for theirs, see
     * [ProfileKeyPull][contact.ProfileKeyPull]. Not always, see
     * [ServedUserProfileDto.isLatest][profile.ServedUserProfileDto.isLatest], but only they can
     * tell, and their answer settles it either way, see [receiveProfileKey]. The name read is
     * saved all the same: off an older version, it is the one they had when that key was
     * replaced, which beats none. A read that opens their latest version settles it too.
     *
     * Every contact is attempted, and the first failure is reported. Only a profile that
     * could not be asked for is one, see [ContactSyncError.Profile].
     */
    suspend fun resolveNames(): EmptyResult<ContactSyncError> {
        var failure: ContactSyncError? = null

        for (contact in contactDao.getDueForRead()) {
            val read = profileRepository.readFullName(
                userUuid = contact.userUuid,
                profileEncryptionKey = contact.profileEncryptionKey,
            )

            when (read) {
                is Result.Success -> {
                    saveName(userUuid = contact.userUuid, fullName = read.data.fullName)

                    if (read.data.isLatest) {
                        pendingProfileKeyDao.delete(userUuid = contact.userUuid)
                    } else {
                        pendingProfileKeyDao.recordStale(
                            userUuid = contact.userUuid,
                            profileEncryptionKey = contact.profileEncryptionKey,
                        )
                    }
                }

                is Result.Error -> when (read.error) {
                    // Pruned since, or never stored: the key opens nothing of theirs any more.
                    // One gone with its owner's account reads the same, and is asked about until
                    // the server says so, see PendingProfileKeyEntity.isAccountGone.
                    ProfileError.NotStored -> pendingProfileKeyDao.recordStale(
                        userUuid = contact.userUuid,
                        profileEncryptionKey = contact.profileEncryptionKey,
                    )

                    // One contact's profile failing says nothing about the next one's.
                    is ProfileError.Network -> failure = failure ?: ContactSyncError.Profile(read.error)

                    // A version that does not open with the key it was asked for under is its
                    // owner's to put right, not a key gone missing.
                    else -> Unit
                }
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Brings back the contacts the vault holds, which a log-out or a reinstall took off
     * this device, then reads their names. Does nothing once they are back, see
     * [authenticationUtils.setContactsToRestore].
     *
     * No channel comes back with them: the group's state lived in the database that is
     * gone, and no Welcome can reach a key package whose secret went with it. The first
     * message to a restored contact builds a new group instead.
     *
     * The identities pinned for them go back into the MLS client first, from the same read
     * as the contacts themselves. The client puts them back when it opens too, see
     * [MlsIdentityRepository], but from a read of its own: a server showing the two reads
     * different books would otherwise bring contacts back without their pins, and the next
     * Welcome naming one of them would pin whatever identity it carries.
     *
     * A contact whose pin disagrees with the one this device already holds comes back
     * blocked, see [IdentityConflictEntity]: the device's pin stays, a pin is never replaced,
     * and the contact would otherwise be reached through an identity the vault does not
     * vouch for.
     *
     * The safety number the user verified with each of them comes back too, see
     * [ContactEntity.verifiedSafetyNumber], and counts again only if it is still the one computed
     * from the identities now pinned, see [SafetyNumbers][contact.SafetyNumbers].
     *
     * Whatever needs to know who the contacts are runs this first: reading the messages, see
     * [DirectMessageSync][message.DirectMessageSync], and sending or accepting a contact
     * request, see [ContactRequestRepository], which would otherwise pin one of them from
     * whatever identity the server hands over.
     *
     * A name that could not be read is not reported: the next [ContactSync][contact.ContactSync]
     * reads it again.
     */
    suspend fun restore(): EmptyResult<ContactSyncError> {
        val restored = restoreMutex.withLock {
            if (!hasContactsToRestore()) return Result.Success(Unit)

            bringBack()
        }

        // Outside the lock: whoever waits on it needs the contacts and their pins, not their
        // names.
        if (restored is Result.Success) resolveNames()

        return restored
    }

    /** Puts back the contacts the vault holds, and their pins, for [restore]. */
    private suspend fun bringBack(): EmptyResult<ContactSyncError> {
        val contacts = when (val read = vaultRepository.getContacts()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(ContactSyncError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(ContactSyncError.Identity(opened.error))
        }

        try {
            contacts.forEach { (userUuid, contact) ->
                val signatureKey = contact.mlsSignaturePublicKey ?: return@forEach

                if (!client.restorePin(peerUuid = userUuid, signatureKey = signatureKey)) {
                    identityConflictDao.insert(IdentityConflictEntity(peerUuid = userUuid))
                }
            }
        } catch (_: MlsException) {
            return Result.Error(ContactSyncError.Storage)
        }

        contactDao.insertNew(
            contacts = contacts.map { (userUuid, contact) ->
                ContactEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = contact.profileEncryptionKey,
                    handedProfileKeyHash = contact.handedProfileKeyHash,
                    verifiedSafetyNumber = contact.verifiedSafetyNumber,
                    isBackedUp = true,
                )
            },
        )

        // Whoever came back may wake the user again, the blocked ones aside.
        wakeList.rebuild()

        clearContactsToRestore()

        return Result.Success(Unit)
    }

    /**
     * Rotates the key to the user's profile for the removals in [rotating], see
     * [ProfileRepository.rotateProfileKey], then records that they no longer wait for it.
     *
     * Only those: a removal the user makes between the rotation and this owes the key another,
     * see [ProfileRepository.oweProfileKeyRotation], the contact it removes having perhaps been
     * handed the new key in between, by a [ProfileKeyDelivery][contact.ProfileKeyDelivery]
     * running alongside. One made while this backup ran, before the rotation, had its key taken
     * by it, and the next backup finds nothing left owed for it.
     */
    private suspend fun rotateProfileKey(rotating: List<ContactEntity>): EmptyResult<ContactSyncError> {
        val userUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(ContactSyncError.Identity(MlsIdentityError.MissingSession))

        val rotation = profileRepository.rotateProfileKey(userUuid = userUuid)

        if (rotation is Result.Error) return Result.Error(ContactSyncError.ProfileKey(rotation.error))

        contactDao.clearProfileKeyRotation(userUuids = rotating.map { it.userUuid })

        return Result.Success(Unit)
    }

    /** Tells the contact [userUuid], in the group [mlsGroupId], that the user removed them. */
    private suspend fun sendRemovalNotice(
        client: MlsClient,
        userUuid: String,
        mlsGroupId: ByteArray,
    ): EmptyResult<RemoveContactError> {
        val message = try {
            client.encryptApplicationMessage(
                mlsGroupId = mlsGroupId,
                payload = ContactRemoval.serialize(),
            )
        } catch (exception: MlsException) {
            if (exception.isRetryable()) return Result.Error(RemoveContactError.Storage)

            // The group is unusable for good, so there is no way left to tell them, and
            // refusing to remove the contact would keep them forever.
            return Result.Success(Unit)
        }

        val send = directMessageApi.sendDirectMessage(
            body = SendDirectMessageBody(
                recipientUserUuid = userUuid,
                ciphertext = DirectMessageEnvelope(mlsGroupId = mlsGroupId, message = message).serialize(),
            ),
        )

        return when (send) {
            is Result.Success -> Result.Success(Unit)

            is Result.Error -> when (val error = send.error) {
                // Whichever of the two limits it was, the user is the one waiting on the
                // removal, so they are told, and try again.
                is SendDirectMessageError.RateLimited -> Result.Error(RemoveContactError.RateLimited)

                is SendDirectMessageError.Network -> when (error.error) {
                    // Their account is gone, and there is nobody left to tell.
                    NetworkError.NOT_FOUND -> Result.Success(Unit)

                    else -> Result.Error(RemoveContactError.Network(error.error))
                }
            }
        }
    }

    /**
     * Removes the contact [userUuid] from this device, leaving a tombstone for [backUp] to
     * carry the removal to the vault, and deletes the groups the two of them shared.
     *
     * Their user stays, hidden from the contact list, rather than being deleted: a user can
     * be more than a contact, and a group the two of them are in still lists them. Their
     * channel row stays too, emptied, see [DirectChannelEntity].
     *
     * What this device is still waiting for from them is noted first: the keys to those
     * expenses are the only ones they can still hand over, see
     * [PendingExpenseKeyEntity.isAwaitedAtRemoval][database.data.local.entity.PendingExpenseKeyEntity.isAwaitedAtRemoval].
     *
     * [rotatesProfileKey] is whether the tombstone waits for the user's profile key to rotate,
     * see [ContactEntity.rotatesProfileKey]. One that does is written once the rotation is owed,
     * under the lock every rotation of the key and edit of the profile takes, see [remove] and
     * [ProfileRepository.oweProfileKeyRotation]: from then on, nothing the user writes goes under
     * the key the contact holds.
     *
     * Nor is their own profile key asked for any longer, see
     * [PendingProfileKeyEntity][database.data.local.entity.PendingProfileKeyEntity].
     */
    private suspend fun forget(userUuid: String, client: MlsClient?, rotatesProfileKey: Boolean) {
        contactDao.get(userUuid = userUuid) ?: return

        // First, so that there is no moment when they are no longer a contact and what they
        // owe is not noted yet: a key of theirs read then would be dropped. And only once they
        // are known to be one: a notice of removal from anybody else would make whatever they
        // put the user on owed.
        pendingExpenseKeyDao.markAwaitedAtRemoval(creditorUuid = userUuid)

        contactDao.markRemoved(userUuid = userUuid, rotatesProfileKey = rotatesProfileKey)

        pendingProfileKeyDao.delete(userUuid = userUuid)

        // Straight away: a former contact must not wake the user a moment longer, see
        // [WakeList].
        wakeList.rebuild()

        userRepository.remove(userUuid = userUuid)

        // Under the peer's lock, which every change to the groups their channel names takes: a
        // group adopted between the read and the clear would be left with nothing naming it.
        withPeerLock(peerUuid = userUuid) {
            val channel = directChannelDao.get(peerUuid = userUuid)

            // The channel row stays, emptied: the two of them may still owe each other money,
            // and a one-time expense they share is opened with a key only the other one has.
            // What goes is the MLS state, which has no use until a new group is built.
            directChannelDao.clear(peerUuid = userUuid)

            val held = listOfNotNull(channel?.mlsGroupId, channel?.previousMlsGroupId, channel?.olderMlsGroupId)

            // A caller that found no group had no client to pass, and one may have been adopted
            // since it looked. A notice of removal passes none either, opened only for a group.
            val deleting = client
                ?: held.takeIf { it.isNotEmpty() }?.let { (mlsIdentityRepository.client() as? Result.Success)?.data }

            held.forEach { deleting?.deleteGroupQuietly(mlsGroupId = it) }
        }
    }

    private suspend fun saveName(userUuid: String, fullName: String) {
        userRepository.insert(
            User(
                uuid = userUuid,
                // Only the user's own is known, a contact's discovery handle never reaches
                // this device.
                username = "",
                fullName = fullName,
            ),
        )
    }
}

/**
 * The changes [ContactRepository.backUp] read, sorted by what each has to wait for.
 *
 * Only the removals the user made wait, for the key to their profile to rotate, see
 * [ContactEntity.rotatesProfileKey]. The rest goes out whatever comes of that.
 */
internal class PendingBackUp(private val contacts: List<ContactEntity>) {

    /** The contacts the vault does not hold as they are on this device. */
    val added: List<ContactEntity> = contacts.filterNot { it.isRemoved }

    /** The removals that wait for the user's profile key to rotate. */
    val rotating: List<ContactEntity> = contacts.filter { it.isRemoved && it.rotatesProfileKey }

    fun isEmpty(): Boolean = contacts.isEmpty()

    /**
     * The removals to write: every one once the key has rotated, and otherwise only those
     * that do not wait for it.
     */
    fun removals(isProfileKeyRotated: Boolean): List<ContactEntity> =
        contacts.filter { it.isRemoved && (isProfileKeyRotated || !it.rotatesProfileKey) }
}
