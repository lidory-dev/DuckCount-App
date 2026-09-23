package vault

import authenticationUtils.getRootKey
import database.data.remote.EncryptedFieldDto
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.EmptyResult
import util.NetworkError
import util.Result
import util.map

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
 * and one part to read one. Both are a single request.
 */
class VaultRepository(
    private val vaultApi: VaultApi,
) {

    /**
     * The key that seals the user's profile, generating it and writing it to the vault
     * the first time, which is when the user first fills their profile in.
     *
     * Idempotent: an account that already holds one gets it back, so calling it again
     * on a later profile edit writes nothing, and it repairs an account whose first
     * attempt never reached the server.
     *
     * Unlike the other keys in the vault this one is meant to be given away.
     */
    suspend fun getOrCreateProfileEncryptionKey(): Result<ByteArray, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val part = VaultEntryKey.PROFILE_ENCRYPTION_KEY

        val read = when (val singleton = readPart(rootKey = rootKey, part = part)) {
            is Result.Success -> singleton.data
            is Result.Error -> return Result.Error(singleton.error)
        }

        read.plaintext?.let { return Result.Success(it) }

        val profileEncryptionKey = generateSymmetricEncryptionKey()

        return writePart(
            rootKey = rootKey,
            keyRing = read.keyRing,
            part = part,
            plaintext = profileEncryptionKey,
        ).map { profileEncryptionKey }
    }

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
     * Each of the user's contacts, keyed by their user UUID. Empty when the vault holds no
     * contact yet.
     */
    suspend fun getContacts(): Result<Map<String, VaultContact>, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val read = when (val singleton = readPart(rootKey = rootKey, part = VaultEntryKey.CONTACTS)) {
            is Result.Success -> singleton.data
            is Result.Error -> return Result.Error(singleton.error)
        }

        val contactBook = when (val plaintext = read.plaintext) {
            null -> ContactBook.EMPTY
            else -> ContactBook.parse(plaintext = plaintext)
                ?: return Result.Error(VaultError.MalformedContactBook)
        }

        return Result.Success(contactBook.contacts)
    }

    /**
     * Adds the contacts in [added], keyed by their user UUID, replacing what the vault
     * already holds for any of them, and drops the contacts in [removed].
     *
     * The contacts are sealed whole, so any change rewrites all of them, and both kinds go
     * in one write. The write goes against the version they were read at, so a contact
     * another device changed in between is refused as [VaultError.Contended] rather than
     * overwritten.
     */
    suspend fun updateContacts(
        added: Map<String, VaultContact>,
        removed: Collection<String>,
    ): EmptyResult<VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val read = when (val singleton = readPart(rootKey = rootKey, part = VaultEntryKey.CONTACTS)) {
            is Result.Success -> singleton.data
            is Result.Error -> return Result.Error(singleton.error)
        }

        val contactBook = when (val plaintext = read.plaintext) {
            null -> ContactBook.EMPTY
            else -> ContactBook.parse(plaintext = plaintext)
                ?: return Result.Error(VaultError.MalformedContactBook)
        }

        val withAdditions = added.entries.fold(contactBook) { book, (userUuid, contact) ->
            book.with(userUuid = userUuid, contact = contact)
        }

        val updated = removed.fold(withAdditions) { book, userUuid ->
            book.without(userUuid = userUuid)
        }

        return writePart(
            rootKey = rootKey,
            keyRing = read.keyRing,
            part = VaultEntryKey.CONTACTS,
            plaintext = updated.serialize(),
            expectedVersion = read.version,
        )
    }

    /**
     * The key sealing [part], generating it and writing the key ring back the first time.
     *
     * What a caller reaches for when it is about to seal something that will leave this
     * device *before* the entry holding it is written: a key that exists only in memory
     * seals bytes nobody can ever open again if the write that would have recorded it
     * never lands. Committing the ring first costs one request and makes that impossible.
     *
     * Unlike the writes built on a previous read, a lost race here is retried rather than
     * reported: the ring another device wrote in between can only have *added* keys, both
     * devices are content with whichever key wins, and a caller that has already decided
     * to seal under this part cannot simply mint a fresh key later.
     *
     * A ring naming a part the vault holds no entry of is an ordinary state, the one every
     * part passes through between its key being recorded and its first entry being written.
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

    /**
     * Every item of a collection [part], unsealed, keyed by its item id. Empty when the
     * vault holds none.
     *
     * One request for the whole part, so a caller that needs several items reads once and
     * looks them up, rather than paying a round trip each.
     *
     * An item that does not open is left out rather than failing the read: a newer build
     * may have written something this one cannot make sense of, and unreadable and absent
     * are the same answer to a caller that only wants what it can use.
     */
    suspend fun readCollection(part: VaultEntryKey): Result<Map<String, ByteArray>, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val entries = when (val vault = vaultApi.getVault(VaultEntryKey.KEY_RING, part)) {
            is Result.Success -> vault.data.entries
            is Result.Error -> return Result.Error(VaultError.Network(vault.error))
        }

        val keyRing = when (val unsealed = unsealKeyRing(rootKey = rootKey, entries = entries)) {
            is Result.Success -> unsealed.data
            is Result.Error -> return Result.Error(unsealed.error)
        }

        val items = entries.filter { it.entryKey == part.wireName }

        if (items.isEmpty()) return Result.Success(emptyMap())

        val sealingKey = keyRing.keyRing.keyFor(part = part)
            ?: return Result.Error(VaultError.OrphanedEntry)

        val plaintexts = buildMap {
            items.forEach { item ->
                unseal(key = sealingKey, content = item.content)?.let { put(item.itemId, it) }
            }
        }

        return Result.Success(plaintexts)
    }

    /**
     * Writes one item of a collection [part], creating it or replacing it in place.
     *
     * Unconditional: an item is written by whoever holds what goes in it, and two devices
     * writing the same one are writing the same bytes. A part that has reached the item
     * cap the server enforces comes back as [VaultError.PartFull], which retrying cannot
     * help.
     */
    suspend fun writeCollectionItem(
        part: VaultEntryKey,
        itemId: String,
        plaintext: ByteArray,
    ): EmptyResult<VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        val keyRing = when (val read = readKeyRing(rootKey = rootKey)) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(read.error)
        }

        return writePart(
            rootKey = rootKey,
            keyRing = keyRing,
            part = part,
            plaintext = plaintext,
            itemId = itemId,
        )
    }

    /**
     * Drops one item of a collection [part].
     *
     * The server's delete is idempotent, so an item that is already gone is a success.
     */
    suspend fun deleteCollectionItem(
        part: VaultEntryKey,
        itemId: String,
    ): EmptyResult<VaultError> =
        when (val delete = vaultApi.deleteVaultEntry(entryKey = part, itemId = itemId)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(VaultError.Network(delete.error))
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
     * The key ring together with the plaintext of one of [part]'s entries, in one request.
     *
     * [itemId] names the entry within a collection part, and defaults to the empty id a
     * singleton's only entry carries.
     *
     * An entry the vault does not hold reads as a null plaintext rather than an error: it
     * is the ordinary state of a part that has never been written.
     */
    private suspend fun readPart(
        rootKey: ByteArray,
        part: VaultEntryKey,
        itemId: String = SINGLETON_ITEM_ID,
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

        val entry = entries.item(part = part, itemId = itemId)
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
        val entry = entries.singleton(part = VaultEntryKey.KEY_RING)
            ?: return Result.Success(KeyRingSnapshot(keyRing = KeyRing.EMPTY, version = null))

        val plaintext = unseal(key = rootKey, content = entry.content)
            ?: return Result.Error(VaultError.Crypto)

        val keyRing = KeyRing.parse(plaintext = plaintext)
            ?: return Result.Error(VaultError.MalformedKeyRing)

        return Result.Success(KeyRingSnapshot(keyRing = keyRing, version = entry.version))
    }

    /**
     * Seals [plaintext] into [part]'s entry, generating the key that seals it and adding
     * it to [keyRing] when the ring does not carry one yet.
     *
     * Reports [VaultError.Contended] when another device grew the key ring in between,
     * or changed the entry since [expectedVersion], a single attempt with no retry: the
     * caller surfaces it and the user tries again.
     *
     * @param itemId The entry within a collection part, defaulting to the empty id a
     * singleton's only entry carries.
     * @param expectedVersion The version the entry was read at, for a write that builds
     * on what it held. Null writes unconditionally.
     */
    private suspend fun writePart(
        rootKey: ByteArray,
        keyRing: KeyRingSnapshot,
        part: VaultEntryKey,
        plaintext: ByteArray,
        itemId: String = SINGLETON_ITEM_ID,
        expectedVersion: Long? = null,
    ): EmptyResult<VaultError> {
        val storedSealingKey = keyRing.keyRing.keyFor(part = part)

        if (storedSealingKey != null) {
            // The ring already names the part, so only the entry it seals has to be
            // written. This is also where a previous attempt that grew the ring and then
            // lost the network resumes from.
            val sealedPart = seal(key = storedSealingKey, plaintext = plaintext)
                ?: return Result.Error(VaultError.Crypto)

            return savePart(
                part = part,
                content = sealedPart,
                itemId = itemId,
                expectedVersion = expectedVersion,
            )
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
                            VaultEntryUploadDto(
                                entryKey = part.wireName,
                                itemId = itemId,
                                content = sealedPart,
                            ),
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

        return savePart(part = part, content = sealedPart, itemId = itemId)
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

    private suspend fun savePart(
        part: VaultEntryKey,
        content: EncryptedFieldDto,
        itemId: String = SINGLETON_ITEM_ID,
        expectedVersion: Long? = null,
    ): EmptyResult<VaultError> =
        when (
            val write = vaultApi.saveVaultEntry(
                entryKey = part,
                body = SaveVaultEntryRequestBody(
                    itemId = itemId,
                    content = content,
                    expectedVersion = expectedVersion,
                ),
            )
        ) {
            is Result.Success -> Result.Success(Unit)

            // The server answers both a stale expected version and a collection part that
            // has reached its item cap with a 409, and says no more than that. They are
            // told apart here by the only thing that distinguishes the two calls: a write
            // that carries no expected version cannot be refused for one, so its conflict
            // is the cap. The distinction matters because one is worth retrying and the
            // other never is.
            is Result.Error -> Result.Error(
                when {
                    write.error != NetworkError.CONFLICT -> VaultError.Network(write.error)
                    expectedVersion != null -> VaultError.Contended
                    else -> VaultError.PartFull
                },
            )
        }

    /**
     * One entry of [part], or null when the vault does not hold it.
     *
     * A singleton's only entry carries the empty item id, so [singleton] is this with the
     * id a singleton always has.
     */
    private fun List<VaultEntryDto>.item(part: VaultEntryKey, itemId: String): VaultEntryDto? =
        firstOrNull { it.entryKey == part.wireName && it.itemId == itemId }

    /** The entry of a singleton part, or null when the vault does not hold it. */
    private fun List<VaultEntryDto>.singleton(part: VaultEntryKey): VaultEntryDto? =
        item(part = part, itemId = SINGLETON_ITEM_ID)
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
