package vault

import authenticationUtils.getRootKey
import database.data.remote.EncryptedFieldDto
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
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
 *                               └─ seals →  profile_encryption_key, group_master_key, ...
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
 */
class VaultRepository(
    private val vaultApi: VaultApi,
) {

    /**
     * The key that seals the user's profile, generating it and writing it to the vault
     * the first time, which is when the user first fills their profile in.
     *
     * Always resolved from the vault, never from a copy kept on the device. The key is
     * only ever used to talk to the server, so a device copy would buy nothing offline
     * while making it possible to hold a key the vault no longer has, and re-keying a
     * profile with one would lock out every contact holding the real key, since the
     * proof of possession the server stores is derived from whatever the client sends.
     *
     * Idempotent: an account that already holds one gets it back, so calling it again
     * on a later profile edit writes nothing, and it repairs an account whose first
     * attempt never reached the server.
     *
     * Unlike the other keys in the vault this one is meant to be given away.
     */
    suspend fun getOrCreateProfileEncryptionKey(): Result<ByteArray, VaultError> {
        val rootKey = getRootKey() ?: return Result.Error(VaultError.MissingRootKey)

        return resolveProfileEncryptionKey(rootKey = rootKey)
    }

    /**
     * The profile encryption key retrieved from the vault, writing it
     * first when the vault does not hold it yet.
     *
     * Reports [VaultError.Contended] when the key ring changed on another device.
     */
    private suspend fun resolveProfileEncryptionKey(rootKey: ByteArray): Result<ByteArray, VaultError> {
        // One full sync rather than a read per part, both entries this needs are in it,
        // and a vault that has never been written comes back empty rather than 404.
        val entries = when (val vault = vaultApi.getVault()) {
            is Result.Success -> vault.data.entries
            is Result.Error -> return Result.Error(VaultError.Network(vault.error))
        }

        val ringEntry = entries.singleton(part = VaultEntryKey.KEY_RING)

        val ring = when (ringEntry) {
            null -> KeyRing.EMPTY
            else -> {
                val plaintext = unseal(key = rootKey, content = ringEntry.content)
                    ?: return Result.Error(VaultError.Crypto)

                KeyRing.parse(plaintext = plaintext)
                    ?: return Result.Error(VaultError.MalformedKeyRing)
            }
        }

        val part = VaultEntryKey.PROFILE_ENCRYPTION_KEY
        val partEntry = entries.singleton(part = part)
        val partSealingKey = ring.keyFor(part = part)

        if (partEntry != null) {
            // The entry is there, so the ring has to name it. Generating a replacement
            // instead would discard a key contacts may already be reading the profile
            // with, so this is reported rather than repaired.
            partSealingKey ?: return Result.Error(VaultError.OrphanedEntry)

            return unseal(key = partSealingKey, content = partEntry.content)
                ?.let { Result.Success(it) }
                ?: Result.Error(VaultError.Crypto)
        }

        return createProfileEncryptionKey(
            rootKey = rootKey,
            keyRing = ring,
            keyRingVersion = ringEntry?.version,
            partSealingKey = partSealingKey,
        )
    }

    /**
     * Generates the profile encryption key and writes it to the vault, adding the key
     * that seals it to the key ring when the key ring does not carry one yet.
     *
     * [partSealingKey] is non-null when a previous attempt got as far as writing the key
     * ring and then lost the network, the key ring already names the part, so only the
     * entry it seals is missing.
     */
    private suspend fun createProfileEncryptionKey(
        rootKey: ByteArray,
        keyRing: KeyRing,
        keyRingVersion: Long?,
        partSealingKey: ByteArray?,
    ): Result<ByteArray, VaultError> {
        val part = VaultEntryKey.PROFILE_ENCRYPTION_KEY
        val profileEncryptionKey = generateSymmetricEncryptionKey()

        if (partSealingKey != null) {
            val sealedPart = seal(key = partSealingKey, plaintext = profileEncryptionKey)
                ?: return Result.Error(VaultError.Crypto)

            return savePart(part = part, content = sealedPart).map { profileEncryptionKey }
        }

        val newPartSealingKey = generateSymmetricEncryptionKey()

        val sealedKeyRing = seal(
            key = rootKey,
            plaintext = keyRing.with(part = part, key = newPartSealingKey).serialize(),
        ) ?: return Result.Error(VaultError.Crypto)

        val sealedPart = seal(key = newPartSealingKey, plaintext = profileEncryptionKey)
            ?: return Result.Error(VaultError.Crypto)

        if (keyRingVersion == null) {
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
                is Result.Success -> Result.Success(profileEncryptionKey)
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
                expectedVersion = keyRingVersion,
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

        // The ring names the part before the part exists, never the other way round, an
        // entry written first and a key ring write that then failed would leave a part no
        // key could ever open.
        return savePart(part = part, content = sealedPart).map { profileEncryptionKey }
    }

    private suspend fun savePart(
        part: VaultEntryKey,
        content: EncryptedFieldDto,
    ): Result<Unit, VaultError> =
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
