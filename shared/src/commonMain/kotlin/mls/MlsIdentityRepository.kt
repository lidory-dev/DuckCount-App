package mls

import authenticationUtils.getAuthenticatedUserUuid
import authenticationUtils.getRootKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.MlsStore
import util.EmptyResult
import util.Result
import vault.VaultRepository

/**
 * The signed-in user's MLS client, opened with the identity their vault holds.
 *
 * The vault is the source of truth for that identity, the device only a copy of it:
 * a log-out wipes the local database and a reinstall loses it, while every peer has
 * pinned the key it carries. So the client is always opened from the vault, restoring
 * the backed-up key when there is one, and backing up the device's key the first time
 * there is none.
 *
 * The same goes for the identities the user pinned for their contacts, which the vault
 * holds too and which go back into the client before anything gets hold of it. Building a
 * channel or joining one pins whatever identity the key package or the Welcome carries, and
 * all of it goes through this client, so one opened without its pins, as a log-out leaves
 * it, would let the server decide who each contact is, the one thing pinning is for.
 *
 * One client is opened per process and kept for the rest of it. That is required, not
 * merely cheaper (opening one derives its storage key with Argon2id): two clients over
 * the same store would interleave writes the library only serialises within one client.
 * It is also enough, since ending a session restarts the process, so a client never
 * outlives the user it was opened for.
 */
class MlsIdentityRepository(
    private val mlsStore: MlsStore,
    private val vaultRepository: VaultRepository,
) {
    private val mutex = Mutex()

    private var opened: MlsClient? = null

    /**
     * The user's MLS client, opening it on the first call.
     *
     * The first call reads the vault, and writes to it on an account that never backed
     * an identity up. A failure leaves nothing cached, so the next call starts over.
     */
    suspend fun client(): Result<MlsClient, MlsIdentityError> =
        mutex.withLock {
            opened?.let { Result.Success(it) } ?: open()
        }

    private suspend fun open(): Result<MlsClient, MlsIdentityError> {
        val client = when (val identity = openIdentity()) {
            is Result.Success -> identity.data
            is Result.Error -> return identity
        }

        val restored = restorePins(client = client)

        if (restored is Result.Error) {
            // Not kept, since nothing may use it without its pins. The next attempt opens it
            // again and restores them again.
            client.close()

            return Result.Error(restored.error)
        }

        opened = client

        return Result.Success(client)
    }

    /** Opens the client with the identity the vault holds, or with the device's own. */
    private suspend fun openIdentity(): Result<MlsClient, MlsIdentityError> {
        val userUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(MlsIdentityError.MissingSession)

        val rootKey = getRootKey() ?: return Result.Error(MlsIdentityError.MissingSession)

        val stored = when (val read = vaultRepository.getMlsSignatureKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(MlsIdentityError.Vault(read.error))
        }

        if (stored != null) {
            val backup = MlsSignatureKeyBackup.parse(plaintext = stored)
                ?: return Result.Error(MlsIdentityError.Crypto)

            val client = openClient {
                MlsClient.restore(
                    userUuid = userUuid,
                    secret = rootKey,
                    store = mlsStore,
                    signaturePrivateKey = backup.privateKey,
                    signaturePublicKey = backup.publicKey,
                )
            } ?: return Result.Error(MlsIdentityError.Crypto)

            return Result.Success(client)
        }

        // Nothing backed up yet, so the identity this device holds becomes the account's,
        // generated on the spot if it holds none either.
        val client = openClient {
            MlsClient(userUuid = userUuid, secret = rootKey, store = mlsStore)
        } ?: return Result.Error(MlsIdentityError.Crypto)

        val backup = MlsSignatureKeyBackup(
            privateKey = client.signaturePrivateKey(),
            publicKey = client.signaturePublicKey(),
        )

        val write = vaultRepository.setMlsSignatureKey(signatureKey = backup.serialize())

        if (write is Result.Error) {
            // Not kept: a client whose identity exists on this device alone is one the
            // next log-out destroys. The key stays in the local store, so the next attempt
            // opens the same one and backs it up.
            client.close()

            return Result.Error(MlsIdentityError.Vault(write.error))
        }

        return Result.Success(client)
    }

    /**
     * Pins in [client] the identity the vault holds for each of the user's contacts.
     *
     * On every open rather than once, which costs a vault read and nothing else: pinning a
     * key already pinned changes nothing, and there is then no state saying the pins are
     * back that could disagree with the store they live in.
     */
    private suspend fun restorePins(client: MlsClient): EmptyResult<MlsIdentityError> {
        val contacts = when (val read = vaultRepository.getContacts()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(MlsIdentityError.Vault(read.error))
        }

        try {
            contacts.forEach { (userUuid, contact) ->
                val signatureKey = contact.mlsSignaturePublicKey ?: return@forEach

                // One that disagrees with a pin already held leaves that pin as it was.
                client.restorePin(peerUuid = userUuid, signatureKey = signatureKey)
            }
        } catch (_: MlsException) {
            return Result.Error(MlsIdentityError.Crypto)
        }

        return Result.Success(Unit)
    }

    /**
     * Runs [open] off the main thread, which the library blocks on its storage and on
     * deriving the storage key, or returns null when the library refuses.
     */
    private suspend fun openClient(open: () -> MlsClient): MlsClient? =
        withContext(Dispatchers.IO) {
            try {
                open()
            } catch (_: MlsException) {
                null
            }
        }
}
