package vault

import util.EmptyResult
import util.Result

/**
 * The key to the user's own profile, as the vault keeps it: what the profile and the contacts
 * it is handed to need of the vault, and nothing more, the key a removal owes it, which a
 * rotation writes there, included. [VaultRepository] is the one there is.
 *
 * Its own interface so that what [ProfileRepository][profile.ProfileRepository] and
 * [ProfileKeyDelivery][contact.ProfileKeyDelivery] do with the key, and in what order, can be
 * checked without a server.
 */
interface ProfileKeyVault {

    /** See [VaultRepository.getOrCreateProfileEncryptionKey]. */
    suspend fun getOrCreateProfileEncryptionKey(): Result<ByteArray, VaultError>

    /** See [VaultRepository.replaceProfileEncryptionKey]. */
    suspend fun replaceProfileEncryptionKey(profileEncryptionKey: ByteArray): EmptyResult<VaultError>

    /** See [VaultRepository.getCachedProfileEncryptionKeyPartialHash]. */
    fun getCachedProfileEncryptionKeyPartialHash(): String?

    /** See [VaultRepository.getPendingProfileEncryptionKey]. */
    suspend fun getPendingProfileEncryptionKey(): ByteArray?

    /** See [VaultRepository.setPendingProfileEncryptionKey]. */
    suspend fun setPendingProfileEncryptionKey(profileEncryptionKey: ByteArray)

    /** See [VaultRepository.dropPendingProfileEncryptionKey]. */
    fun dropPendingProfileEncryptionKey()
}
