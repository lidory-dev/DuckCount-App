package vault

import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Holds the key to the user's profile for the length of a test, as the vault and what the
 * device keeps of it would: the hash of the vault's key, and the key a removal owes the profile,
 * which a rotation writes there.
 *
 * Every read and replacement is written to [events], along with whatever else the test logs
 * there, so the order things happened in can be checked.
 */
internal class InMemoryProfileKeyVault(
    var profileEncryptionKey: ByteArray,
    private val events: MutableList<String> = mutableListOf(),
) : ProfileKeyVault {

    /** The hash the device keeps, which a replacement drops and writes again as the vault does. */
    var cachedPartialHash: String? = profileEncryptionKeyPartialHash(profileEncryptionKey)

    /** The key a removal owes the profile, as the device keeps it. */
    var pendingKey: ByteArray? = null

    /** Whether replacing the key fails, as a vault out of reach would. */
    var failsReplacement = false

    /**
     * Whether replacing the key lands and then reports a failure all the same, as a write whose
     * answer was lost on the way back would.
     */
    var losesReplacementAnswer = false

    /**
     * Whether the device is locked, which the pending key cannot be kept on: KSafe throws, the
     * key being kept with a write the device has to be unlocked for.
     */
    var isDeviceLocked = false

    /** How many times the key was read. */
    var reads = 0
        private set

    override suspend fun getOrCreateProfileEncryptionKey(): Result<ByteArray, VaultError> {
        reads++
        events += "vault read"

        cachedPartialHash = profileEncryptionKeyPartialHash(profileEncryptionKey)

        return Result.Success(profileEncryptionKey)
    }

    override suspend fun replaceProfileEncryptionKey(profileEncryptionKey: ByteArray): EmptyResult<VaultError> {
        val partialHash = profileEncryptionKeyPartialHash(profileEncryptionKey)

        events += "vault replace $partialHash"

        cachedPartialHash = null

        if (failsReplacement) return Result.Error(VaultError.Network(NetworkError.UNREACHABLE))

        this.profileEncryptionKey = profileEncryptionKey

        if (losesReplacementAnswer) return Result.Error(VaultError.Network(NetworkError.REQUEST_TIMEOUT))

        cachedPartialHash = partialHash

        return Result.Success(Unit)
    }

    override fun getCachedProfileEncryptionKeyPartialHash(): String? = cachedPartialHash

    override suspend fun getPendingProfileEncryptionKey(): ByteArray? = pendingKey

    override suspend fun setPendingProfileEncryptionKey(profileEncryptionKey: ByteArray) {
        check(!isDeviceLocked) { "KSafe: Cannot access Keystore key - device is locked." }

        events += "pending ${profileEncryptionKeyPartialHash(profileEncryptionKey)}"

        pendingKey = profileEncryptionKey
    }

    override fun dropPendingProfileEncryptionKey() {
        events += "pending dropped"

        pendingKey = null
    }
}
