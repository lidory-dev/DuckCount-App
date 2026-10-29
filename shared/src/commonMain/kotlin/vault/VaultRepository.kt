package vault

import authenticationUtils.clearProfileKeyPartialHash
import authenticationUtils.dropPendingProfileKey
import authenticationUtils.getPendingProfileKey
import authenticationUtils.getProfileKeyPartialHash
import authenticationUtils.getRootKey
import authenticationUtils.setPendingProfileKey
import authenticationUtils.setProfileKeyPartialHash
import database.data.remote.EncryptedFieldDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import util.map
import util.onSuccess

/**
 * The user's key material, as the server holds it: one sealed entry per part, none
 * of which the server can read.
 *
 * Three layers hang off each other, and this is where they are put together:
 *
 * ```
 * OPAQUE export key   password-derived, only ever held during a registration or a login
 *   └─ seals →  root_key    kept on the device, never re-derivable without the password
 *                 └─ seals →  key_ring    one key per part, grows with the feature set
 *                               └─ seals →  profile_encryption_key, discovery_username, ...
 * ```
 *
 * The root key layer exists so that a password change rewrites one entry whose shape
 * no app version ever changes. Were the ring itself password-sealed, an older build
 * changing the password would re-seal it from its own understanding of the plaintext
 * and drop the keys a newer build had added, orphaning their parts for good.
 *
 * Sealing is AES-256-GCM throughout, done in `lib-duckcount`. Nothing here derives a
 * key from another: each part gets its own random one, so handing a contact the key
 * to a profile says nothing about the keys to anything else.
 *
 * Every call starts by reading the vault, never a copy kept on the device, and reads
 * only the parts it is about to use: the key ring on its own to write, the key ring
 * and one part to read one. Both are a single request. The one exception is the hash of
 * the profile key, kept so that a sync with nobody to hand the key to reads nothing, see
 * [getCachedProfileEncryptionKeyPartialHash]. The device also keeps a profile key the vault
 * does not hold yet, which is no copy of it: the one a removal owes it, see
 * [getPendingProfileEncryptionKey].
 */
class VaultRepository(
    private val vaultApi: VaultApi,
) : ProfileKeyVault, MemberProfileVault {

    /**
     * Serializes reading and replacing the profile key, which both end with writing down the
     * hash of the key they saw, see [getCachedProfileEncryptionKeyPartialHash]. A read that
     * fetched the old key before a replacement and wrote its hash down after would leave the
     * device naming a key the vault no longer holds.
     */
    private val profileKeyMutex = Mutex()

    /**
     * The key that seals the user's profile, generating it and writing it to the vault
     * the first time, which is when the user first fills their profile in.
     *
     * Idempotent: an account that already holds one gets it back, so calling it again
     * on a later profile edit writes nothing, and it repairs an account whose first
     * attempt never reached the server.
     *
     * Unlike the other keys in the vault this one is meant to be given away, and replaced
     * when somebody it was given to should no longer hold it, see
     * [replaceProfileEncryptionKey].
     */
    override suspend fun getOrCreateProfileEncryptionKey(): Result<ByteArray, VaultError> =
        profileKeyMutex.withLock {
            val rootKey = getRootKey() ?: return@withLock Result.Error(VaultError.MissingRootKey)

            val part = VaultEntryKey.PROFILE_ENCRYPTION_KEY

            val read = when (val partRead = readPart(rootKey = rootKey, part = part)) {
                is Result.Success -> partRead.data
                is Result.Error -> return@withLock Result.Error(partRead.error)
            }

            read.plaintext?.let { stored ->
                setProfileKeyPartialHash(partialHash = profileEncryptionKeyPartialHash(stored))

                return@withLock Result.Success(stored)
            }

            val profileEncryptionKey = generateSymmetricEncryptionKey()

            writePart(
                rootKey = rootKey,
                keyRing = read.keyRing,
                part = part,
                plaintext = profileEncryptionKey,
            ).map { profileEncryptionKey }.onSuccess {
                setProfileKeyPartialHash(partialHash = profileEncryptionKeyPartialHash(it))
            }
        }

    /**
     * Records [profileEncryptionKey] as the key that seals the user's profile, replacing the one
     * the vault held, which is what rotating it comes down to, see
     * [ProfileRepository.rotateProfileKey][profile.ProfileRepository.rotateProfileKey].
     *
     * Unconditional, like [setDiscoveryUsername]: the caller has already uploaded the
     * profile sealed under the new key, which is what makes it the one to hold, whatever the
     * entry held before.
     *
     * The hash this device kept of the old key goes before the vault is written, so that a
     * failure from there on leaves it naming no key rather than the old one: the next
     * [ProfileKeyDelivery][contact.ProfileKeyDelivery] then reads the vault to learn which.
     */
    override suspend fun replaceProfileEncryptionKey(profileEncryptionKey: ByteArray): EmptyResult<VaultError> =
        profileKeyMutex.withLock {
            val rootKey = getRootKey() ?: return@withLock Result.Error(VaultError.MissingRootKey)

            val keyRing = when (val read = readKeyRing(rootKey = rootKey)) {
                is Result.Success -> read.data
                is Result.Error -> return@withLock Result.Error(read.error)
            }

            clearProfileKeyPartialHash()

            writePart(
                rootKey = rootKey,
                keyRing = keyRing,
                part = VaultEntryKey.PROFILE_ENCRYPTION_KEY,
                plaintext = profileEncryptionKey,
            ).onSuccess {
                setProfileKeyPartialHash(partialHash = profileEncryptionKeyPartialHash(profileEncryptionKey))
            }
        }

    /**
     * The partial hash of the key that seals the user's profile as this device last read or
     * wrote it, or null when it holds none, see [authenticationUtils.setProfileKeyPartialHash].
     *
     * The one answer here not read from the vault: it is asked on every sync, to tell whether
     * any contact has yet to be handed the key, and almost never is one. A hash says nothing the
     * server does not already know, it is what every read of the profile is gated on.
     */
    override fun getCachedProfileEncryptionKeyPartialHash(): String? = getProfileKeyPartialHash()

    /**
     * The key a removal the user made owes their profile key, kept on the device from the removal
     * until the vault holds it, or null when no rotation is owed, see
     * [ProfileRepository.oweProfileKeyRotation][profile.ProfileRepository.oweProfileKeyRotation]
     * and [authenticationUtils.setPendingProfileKey].
     */
    override suspend fun getPendingProfileEncryptionKey(): ByteArray? = getPendingProfileKey()

    /**
     * Keeps [profileEncryptionKey] as the pending key, written before this returns, or throwing on
     * a device locked by then, see [authenticationUtils.setPendingProfileKey].
     */
    override suspend fun setPendingProfileEncryptionKey(profileEncryptionKey: ByteArray) =
        setPendingProfileKey(profileEncryptionKey = profileEncryptionKey)

    /**
     * Drops the pending key, gone from every read before this returns, if not yet from the disk,
     * see [authenticationUtils.dropPendingProfileKey].
     */
    override fun dropPendingProfileEncryptionKey() = dropPendingProfileKey()

    /**
     * The handle the user reserved to be discoverable by, or null when the vault holds
     * none because they never reserved one.
     */
    suspend fun getDiscoveryUsername(): Result<String?, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        return readPart(rootKey = rootKey, part = VaultEntryKey.DISCOVERY_USERNAME)
            .map { read -> read.plaintext?.decodeToString() }
    }

    /**
     * Records [username] as the handle the user is discoverable by, replacing whatever
     * the vault held before.
     *
     * Expected to be the normalized form, which is what the digest the server stores is
     * computed from: this entry only exists to be shown back to the user, so it has to
     * name the same handle the reservation does.
     */
    suspend fun setDiscoveryUsername(username: String): EmptyResult<VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        // Replacing an entry is unconditional, so what it holds today is of no interest
        // and only the key ring is read.
        val keyRing = when (val read = readKeyRing(rootKey = rootKey)) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(read.error)
        }

        return writePart(
            rootKey = rootKey,
            keyRing = keyRing,
            part = VaultEntryKey.DISCOVERY_USERNAME,
            plaintext = username.encodeToByteArray(),
        )
    }

    /**
     * The backed-up MLS signature key pair, still encoded, or null when the vault holds
     * none because this account never backed one up.
     */
    suspend fun getMlsSignatureKey(): Result<ByteArray?, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        return readPart(rootKey = rootKey, part = VaultEntryKey.MLS_SIGNATURE_KEY)
            .map { read -> read.plaintext }
    }

    /**
     * Backs up the MLS signature key pair, replacing whatever the vault held before.
     */
    suspend fun setMlsSignatureKey(signatureKey: ByteArray): EmptyResult<VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val keyRing = when (val read = readKeyRing(rootKey = rootKey)) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(read.error)
        }

        return writePart(
            rootKey = rootKey,
            keyRing = keyRing,
            part = VaultEntryKey.MLS_SIGNATURE_KEY,
            plaintext = signatureKey,
        )
    }

    /**
     * The user's contacts as the vault holds them, see [ContactBook]: each contact, and the name of
     * each former one. Empty when the vault holds no contact yet.
     */
    suspend fun getContactBook(): Result<ContactBook, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val read = when (val partRead = readPart(rootKey = rootKey, part = VaultEntryKey.CONTACTS)) {
            is Result.Success -> partRead.data
            is Result.Error -> return Result.Error(partRead.error)
        }

        val contactBook = when (val plaintext = read.plaintext) {
            null -> ContactBook.EMPTY
            else -> ContactBook.parse(plaintext = plaintext)
                ?: return Result.Error(VaultError.MalformedContactBook)
        }

        return Result.Success(contactBook)
    }

    /**
     * Adds the contacts in [added], keyed by their user UUID, replacing what the vault
     * already holds for any of them, and keeps of each contact in [removed], a former contact
     * from then on, nothing but the name it maps them to, or nothing at all when it maps them
     * to none, see [ContactBook.withFormerContact].
     *
     * Returns the contacts in [added] the vault pins another identity for than the one they
     * come with, which stay as the vault held them, see [ContactBook.with].
     *
     * The contacts are sealed whole, so any change rewrites all of them, and every kind goes
     * in one write. The write goes against the version they were read at, so a contact
     * another device changed in between is refused as [VaultError.Contended] rather than
     * overwritten.
     */
    suspend fun updateContacts(
        added: Map<String, VaultContact>,
        removed: Map<String, String?>,
    ): Result<Set<String>, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val read = when (val partRead = readPart(rootKey = rootKey, part = VaultEntryKey.CONTACTS)) {
            is Result.Success -> partRead.data
            is Result.Error -> return Result.Error(partRead.error)
        }

        val contactBook = when (val plaintext = read.plaintext) {
            null -> ContactBook.EMPTY
            else -> ContactBook.parse(plaintext = plaintext)
                ?: return Result.Error(VaultError.MalformedContactBook)
        }

        val update = contactBook.withChanges(added = added, removed = removed)

        val write = writePart(
            rootKey = rootKey,
            keyRing = read.keyRing,
            part = VaultEntryKey.CONTACTS,
            plaintext = update.book.serialize(),
            expectedVersion = read.version,
        )

        return when (write) {
            is Result.Success -> Result.Success(update.conflicting)
            is Result.Error -> write
        }
    }

    /**
     * The keys to the profiles of the members of the user's groups as the vault holds them, see
     * [MemberProfileBook]. Empty when the vault holds none yet.
     */
    override suspend fun getMemberProfileBook(): Result<MemberProfileBook, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        return when (val partRead = readPart(rootKey = rootKey, part = VaultEntryKey.MEMBER_PROFILE_KEYS)) {
            is Result.Success -> memberProfileBookOf(read = partRead.data)
            is Result.Error -> Result.Error(partRead.error)
        }
    }

    /**
     * Writes each member in [added], keyed by their user UUID, into what the vault holds, see
     * [MemberProfileBook.with]: a key from its owner the vault holds is not replaced by a relayed
     * one.
     *
     * The members are sealed whole, so any change rewrites all of them, in one write against the
     * version they were read at: a member another device wrote in between is refused as
     * [VaultError.Contended] rather than overwritten, for the caller to read and merge again.
     */
    override suspend fun updateMemberProfiles(added: Map<String, VaultMemberProfile>): EmptyResult<VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val read = when (val partRead = readPart(rootKey = rootKey, part = VaultEntryKey.MEMBER_PROFILE_KEYS)) {
            is Result.Success -> partRead.data
            is Result.Error -> return Result.Error(partRead.error)
        }

        val book = when (val parsed = memberProfileBookOf(read = read)) {
            is Result.Success -> parsed.data
            is Result.Error -> return Result.Error(parsed.error)
        }

        return writePart(
            rootKey = rootKey,
            keyRing = read.keyRing,
            part = VaultEntryKey.MEMBER_PROFILE_KEYS,
            plaintext = book.withChanges(added = added).serialize(),
            expectedVersion = read.version,
        )
    }

    /** The book [read] of [VaultEntryKey.MEMBER_PROFILE_KEYS] holds, empty when the vault holds none. */
    private fun memberProfileBookOf(read: PartRead): Result<MemberProfileBook, VaultError> =
        when (val plaintext = read.plaintext) {
            null -> Result.Success(MemberProfileBook.EMPTY)

            else -> MemberProfileBook.parse(plaintext = plaintext)?.let { Result.Success(it) }
                ?: Result.Error(VaultError.MalformedMemberProfileBook)
        }

    /**
     * The key sealing [part], generating it and writing the key ring back the first time.
     *
     * What a caller reaches for when it is about to seal something that will leave this
     * device before anything records the key: a key that exists only in memory seals bytes
     * nobody can ever open again if the write that would have recorded it never lands.
     * Committing the ring first costs one request and makes that impossible.
     *
     * Unlike the writes built on a previous read, a lost race here is retried rather than
     * reported: the ring another device wrote in between can only have *added* keys, both
     * devices are content with whichever key wins, and a caller that has already decided
     * to seal under this part cannot simply mint a fresh key later.
     *
     * A ring naming a part the vault holds no entry of is an ordinary state: a part between
     * its key being recorded and its first entry being written, or
     * [VaultEntryKey.ONE_TIME_EXPENSE_KEY] and [VaultEntryKey.GROUP_MASTER_KEY], whose sealed
     * copies the server keeps with each expense and each group rather than in the vault.
     */
    suspend fun getOrCreatePartKey(part: VaultEntryKey): Result<ByteArray, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        repeat(KEY_RING_GROWTH_ATTEMPTS) {
            val keyRing = when (val read = readKeyRing(rootKey = rootKey)) {
                is Result.Success -> read.data
                is Result.Error -> return Result.Error(read.error)
            }

            keyRing.keyRing.keyFor(part = part)?.let { return Result.Success(it) }

            val sealingKey = generateSymmetricEncryptionKey()

            val growth = growKeyRing(
                rootKey = rootKey,
                keyRing = keyRing,
                part = part,
                sealingKey = sealingKey,
            )

            when {
                growth is Result.Success -> return Result.Success(sealingKey)
                growth is Result.Error && growth.error != VaultError.Contended ->
                    return Result.Error(growth.error)
            }
        }

        return Result.Error(VaultError.Contended)
    }

    /** The key ring on its own, which is everything a write needs. */
    private suspend fun readKeyRing(rootKey: ByteArray): Result<KeyRingSnapshot, VaultError> {
        val entries = when (val vault = vaultApi.getVault(VaultEntryKey.KEY_RING)) {
            is Result.Success -> vault.data.entries
            is Result.Error -> return Result.Error(VaultError.Network(vault.error))
        }

        return unsealKeyRing(rootKey = rootKey, entries = entries)
    }

    /**
     * The key ring together with the plaintext of [part], in one request.
     *
     * An entry the vault does not hold reads as a null plaintext rather than an error: it
     * is the ordinary state of a part that has never been written.
     */
    private suspend fun readPart(
        rootKey: ByteArray,
        part: VaultEntryKey,
    ): Result<PartRead, VaultError> {
        // A vault that has never been written comes back empty rather than 404.
        val entries = when (val vault = vaultApi.getVault(VaultEntryKey.KEY_RING, part)) {
            is Result.Success -> vault.data.entries
            is Result.Error -> return Result.Error(VaultError.Network(vault.error))
        }

        val keyRing = when (val unsealed = unsealKeyRing(rootKey = rootKey, entries = entries)) {
            is Result.Success -> unsealed.data
            is Result.Error -> return Result.Error(unsealed.error)
        }

        val entry = entries.entryOf(part = part)
            ?: return Result.Success(PartRead(keyRing = keyRing, plaintext = null, version = null))

        // The entry is there, so the ring has to name it. Reading is where this is
        // reported rather than repaired: replacing the key would discard whatever the
        // entry holds.
        val sealingKey = keyRing.keyRing.keyFor(part = part)
            ?: return Result.Error(VaultError.OrphanedEntry)

        val plaintext = unseal(key = sealingKey, content = entry.content)
            ?: return Result.Error(VaultError.Crypto)

        return Result.Success(
            PartRead(keyRing = keyRing, plaintext = plaintext, version = entry.version),
        )
    }

    /** Unseals the key ring out of [entries], which must have been read with it. */
    private fun unsealKeyRing(
        rootKey: ByteArray,
        entries: List<VaultEntryDto>,
    ): Result<KeyRingSnapshot, VaultError> {
        val entry = entries.entryOf(part = VaultEntryKey.KEY_RING)
            ?: return Result.Success(KeyRingSnapshot(keyRing = KeyRing.EMPTY, version = null))

        val plaintext = unseal(key = rootKey, content = entry.content)
            ?: return Result.Error(VaultError.Crypto)

        val keyRing = KeyRing.parse(plaintext = plaintext)
            ?: return Result.Error(VaultError.MalformedKeyRing)

        return Result.Success(KeyRingSnapshot(keyRing = keyRing, version = entry.version))
    }

    /**
     * Seals [plaintext] into [part], generating the key that seals it and adding it to
     * [keyRing] when the ring does not carry one yet.
     *
     * Reports [VaultError.Contended] when another device grew the key ring in between,
     * or changed the entry since [expectedVersion], a single attempt with no retry: the
     * caller surfaces it and the user tries again.
     *
     * @param expectedVersion The version the entry was read at, for a write that builds
     * on what it held. Null writes unconditionally.
     */
    private suspend fun writePart(
        rootKey: ByteArray,
        keyRing: KeyRingSnapshot,
        part: VaultEntryKey,
        plaintext: ByteArray,
        expectedVersion: Long? = null,
    ): EmptyResult<VaultError> {
        val storedSealingKey = keyRing.keyRing.keyFor(part = part)

        if (storedSealingKey != null) {
            // The ring already names the part, so only the entry it seals has to be
            // written. This is also where a previous attempt that grew the ring and then
            // lost the network resumes from.
            val sealedPart = seal(key = storedSealingKey, plaintext = plaintext)
                ?: return Result.Error(VaultError.Crypto)

            return savePart(part = part, content = sealedPart, expectedVersion = expectedVersion)
        }

        val sealingKey = generateSymmetricEncryptionKey()

        val sealedKeyRing = seal(
            key = rootKey,
            plaintext = keyRing.keyRing.with(part = part, key = sealingKey).serialize(),
        ) ?: return Result.Error(VaultError.Crypto)

        val sealedPart = seal(key = sealingKey, plaintext = plaintext)
            ?: return Result.Error(VaultError.Crypto)

        if (keyRing.version == null) {
            // Nothing but the root key in there yet, this is the vault being set up, so
            // the ring and the first key it seals go in one transaction and the vault is
            // never left holding one without the other.
            return when (
                val patch = vaultApi.patchVault(
                    body = VaultPatchBody(
                        upserts = listOf(
                            VaultEntryUploadDto(
                                entryKey = VaultEntryKey.KEY_RING.wireName,
                                content = sealedKeyRing,
                            ),
                            VaultEntryUploadDto(entryKey = part.wireName, content = sealedPart),
                        ),
                    ),
                )
            ) {
                is Result.Success -> Result.Success(Unit)
                is Result.Error -> Result.Error(VaultError.Network(patch.error))
            }
        }

        // The key ring is already there and has to grow by one key, and only then does
        // the entry it seals go in.
        val growth = growKeyRing(rootKey = rootKey, keyRing = keyRing, part = part, sealingKey = sealingKey)

        if (growth is Result.Error) return growth

        return savePart(part = part, content = sealedPart)
    }

    /**
     * Adds [sealingKey] to [keyRing] as the key sealing [part], and writes the ring back.
     *
     * Writing it against the version it was read at is what keeps a key another device
     * added in between from being dropped: the server refuses the write rather than taking
     * a ring that no longer descends from what is stored. That refusal is
     * [VaultError.Contended], and what a caller does about it differs, so it is reported
     * rather than retried here.
     */
    private suspend fun growKeyRing(
        rootKey: ByteArray,
        keyRing: KeyRingSnapshot,
        part: VaultEntryKey,
        sealingKey: ByteArray,
    ): EmptyResult<VaultError> {
        val sealedKeyRing = seal(
            key = rootKey,
            plaintext = keyRing.keyRing.with(part = part, key = sealingKey).serialize(),
        ) ?: return Result.Error(VaultError.Crypto)

        val write = vaultApi.saveVaultEntry(
            entryKey = VaultEntryKey.KEY_RING,
            body = SaveVaultEntryRequestBody(
                content = sealedKeyRing,
                expectedVersion = keyRing.version,
            ),
        )

        if (write is Result.Error) {
            return Result.Error(
                when (write.error) {
                    NetworkError.CONFLICT -> VaultError.Contended
                    else -> VaultError.Network(write.error)
                },
            )
        }

        return Result.Success(Unit)
    }

    /** Writes [content] as [part]. A `409` can only be [expectedVersion] gone stale. */
    private suspend fun savePart(
        part: VaultEntryKey,
        content: EncryptedFieldDto,
        expectedVersion: Long? = null,
    ): EmptyResult<VaultError> =
        when (
            val write = vaultApi.saveVaultEntry(
                entryKey = part,
                body = SaveVaultEntryRequestBody(content = content, expectedVersion = expectedVersion),
            )
        ) {
            is Result.Success -> Result.Success(Unit)

            is Result.Error -> Result.Error(
                when (write.error) {
                    NetworkError.CONFLICT -> VaultError.Contended
                    else -> VaultError.Network(write.error)
                },
            )
        }

    /** The entry of [part], or null when the vault does not hold it. */
    private fun List<VaultEntryDto>.entryOf(part: VaultEntryKey): VaultEntryDto? =
        firstOrNull { it.entryKey == part.wireName }
}

/**
 * How many times [VaultRepository.getOrCreatePartKey] re-reads a key ring another device
 * grew underneath it before giving up. Each round loses only to a device writing at the
 * same instant, so a handful is already far more than a real race needs.
 */
private const val KEY_RING_GROWTH_ATTEMPTS = 3

/**
 * The key ring, unsealed, and the version it was read at.
 *
 * The version travels with it because it is what a write has to send back: it is the
 * only thing that keeps a key another device added in between from being dropped.
 */
private class KeyRingSnapshot(
    val keyRing: KeyRing,

    /** Null when the vault carries no key ring yet, so it has never been set up. */
    val version: Long?,
)

/**
 * One entry of a part, read alongside the key ring that opens it.
 *
 * The ring is kept rather than discarded so that a caller which reads a part in order
 * to create it can write it back without reading the vault a second time.
 */
private class PartRead(
    val keyRing: KeyRingSnapshot,

    /** Null when the vault does not hold the entry yet. */
    val plaintext: ByteArray?,

    /**
     * The version the entry was read at, for a write that builds on [plaintext].
     * Null when the vault does not hold the entry yet.
     */
    val version: Long?,
)
