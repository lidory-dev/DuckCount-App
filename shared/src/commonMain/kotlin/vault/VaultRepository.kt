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

        val read = when (val singleton = readSingleton(rootKey = rootKey, part = part)) {
            is Result.Success -> singleton.data
            is Result.Error -> return Result.Error(singleton.error)
        }

        read.plaintext?.let { return Result.Success(it) }

        val profileEncryptionKey = generateSymmetricEncryptionKey()

        return writeSingleton(
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

        return readSingleton(rootKey = rootKey, part = VaultEntryKey.DISCOVERY_USERNAME)
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

        return writeSingleton(
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

        return readSingleton(rootKey = rootKey, part = VaultEntryKey.MLS_SIGNATURE_KEY)
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

        return writeSingleton(
            rootKey = rootKey,
            keyRing = keyRing,
            part = VaultEntryKey.MLS_SIGNATURE_KEY,
            plaintext = signatureKey,
        )
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
     * The key ring together with the plaintext of [part]'s entry, in one request.
     *
     * A part the vault does not hold reads as a null plaintext rather than an error: it
     * is the ordinary state of a part that has never been written.
     */
    private suspend fun readSingleton(
        rootKey: ByteArray,
        part: VaultEntryKey,
    ): Result<SingletonRead, VaultError> {
        // A vault that has never been written comes back empty rather than 404.
        val entries = when (val vault = vaultApi.getVault(VaultEntryKey.KEY_RING, part)) {
            is Result.Success -> vault.data.entries
            is Result.Error -> return Result.Error(VaultError.Network(vault.error))
        }

        val keyRing = when (val unsealed = unsealKeyRing(rootKey = rootKey, entries = entries)) {
            is Result.Success -> unsealed.data
            is Result.Error -> return Result.Error(unsealed.error)
        }

        val entry = entries.singleton(part = part)
            ?: return Result.Success(SingletonRead(keyRing = keyRing, plaintext = null))

        // The entry is there, so the ring has to name it. Reading is where this is
        // reported rather than repaired: replacing the key would discard whatever the
        // entry holds.
        val sealingKey = keyRing.keyRing.keyFor(part = part)
            ?: return Result.Error(VaultError.OrphanedEntry)

        val plaintext = unseal(key = sealingKey, content = entry.content)
            ?: return Result.Error(VaultError.Crypto)

        return Result.Success(SingletonRead(keyRing = keyRing, plaintext = plaintext))
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
     * a single attempt with no retry: the caller surfaces it and the user tries again.
     */
    private suspend fun writeSingleton(
        rootKey: ByteArray,
        keyRing: KeyRingSnapshot,
        part: VaultEntryKey,
        plaintext: ByteArray,
    ): EmptyResult<VaultError> {
        val storedSealingKey = keyRing.keyRing.keyFor(part = part)

        if (storedSealingKey != null) {
            // The ring already names the part, so only the entry it seals has to be
            // written. This is also where a previous attempt that grew the ring and then
            // lost the network resumes from.
            val sealedPart = seal(key = storedSealingKey, plaintext = plaintext)
                ?: return Result.Error(VaultError.Crypto)

            return savePart(part = part, content = sealedPart)
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

        // The key ring is already there and has to grow by one key. Writing it against
        // the version it was read at is what keeps a key another device added in between
        // from being dropped. The server refuses the write rather than taking a ring
        // that no longer descends from what is stored.
        val keyRingWrite = vaultApi.saveVaultEntry(
            entryKey = VaultEntryKey.KEY_RING,
            body = SaveVaultEntryRequestBody(
                content = sealedKeyRing,
                expectedVersion = keyRing.version,
            ),
        )

        if (keyRingWrite is Result.Error) {
            return Result.Error(
                when (keyRingWrite.error) {
                    NetworkError.CONFLICT -> VaultError.Contended
                    else -> VaultError.Network(keyRingWrite.error)
                },
            )
        }

        return savePart(part = part, content = sealedPart)
    }

    private suspend fun savePart(
        part: VaultEntryKey,
        content: EncryptedFieldDto,
    ): EmptyResult<VaultError> =
        when (
            val write = vaultApi.saveVaultEntry(
                entryKey = part,
                body = SaveVaultEntryRequestBody(content = content),
            )
        ) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(VaultError.Network(write.error))
        }

    /** The entry of a singleton part, or null when the vault does not hold it. */
    private fun List<VaultEntryDto>.singleton(part: VaultEntryKey): VaultEntryDto? =
        firstOrNull { it.entryKey == part.wireName && it.itemId == SINGLETON_ITEM_ID }
}

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
 * One singleton part read alongside the key ring that opens it.
 *
 * The ring is kept rather than discarded so that a caller which reads a part in order
 * to create it can write it back without reading the vault a second time.
 */
private class SingletonRead(
    val keyRing: KeyRingSnapshot,

    /** Null when the vault does not hold the part yet. */
    val plaintext: ByteArray?,
)
