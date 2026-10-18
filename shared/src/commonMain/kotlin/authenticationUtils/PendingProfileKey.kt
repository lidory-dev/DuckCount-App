package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin
import kotlin.io.encoding.Base64

const val PENDING_PROFILE_KEY = "pendingProfileKey"

/**
 * Keeps the key a removal the user made owes their profile key, from the removal until the
 * vault holds it, see `ProfileRepository.oweProfileKeyRotation`, replacing whatever key was kept
 * before.
 *
 * So that an edit made before the rotation goes up under it, and every attempt at the rotation
 * under the one key: an upload that lands followed by a vault write that does not leaves a
 * version under a key the vault does not hold, and one such version per attempt would end up
 * pruning the version the vault's key opens, see `ProfileRepository.rotateProfileKey`.
 * `VaultRepository` owns it, and nothing else has business writing here. Dropped on log out,
 * which leaves at most that one version unread.
 *
 * Suspends until the write is done rather than using the fire-and-forget `putDirect`: the removal
 * is written down only once this returns, and a key lost with the process after that would leave
 * it waiting for a rotation with no key owed, which the rotation takes for one already done. The
 * contact removed would go on holding the current key, and read every edit after. Written before
 * the contact is told of the removal, since it throws on a device locked by then, see
 * `ProfileRepository.oweProfileKeyRotation`.
 *
 * Stored Base64 encoded, since KSafe holds primitives rather than byte arrays.
 */
suspend fun setPendingProfileKey(profileEncryptionKey: ByteArray) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.put(
        key = PENDING_PROFILE_KEY,
        value = Base64.Default.encode(profileEncryptionKey),
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

/**
 * Drops the key once the vault holds it, see `ProfileRepository.rotateProfileKey`.
 *
 * With the fire-and-forget `deleteDirect`, unlike the write that kept it, see
 * [setPendingProfileKey]: gone from every read at once, nothing hangs on the delete reaching the
 * disk. A key that outlived the process, the delete never having landed, is the very key the
 * vault holds by then, which the next edit or rotation finds there and drops in turn, see
 * `ProfileRepository.getOwedKey`, as it does after a vault write whose answer was lost. Awaited,
 * the delete would only add a way for a rotation already done to fail.
 */
fun dropPendingProfileKey() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = PENDING_PROFILE_KEY,
    )
}

/** Drops the key on log out, with the rest of the session. */
fun clearPendingProfileKey() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = PENDING_PROFILE_KEY,
    )
}

/** The pending key, or null when no rotation is owed. See [getRootKey] on the type argument. */
suspend fun getPendingProfileKey(): ByteArray? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<String?>(PENDING_PROFILE_KEY, defaultValue = null)?.let { encoded: String ->
        Base64.Default.decode(encoded)
    }
}
