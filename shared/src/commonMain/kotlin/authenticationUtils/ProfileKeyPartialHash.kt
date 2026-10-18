package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val PROFILE_KEY_PARTIAL_HASH = "profileKeyPartialHash"

/**
 * Caches the partial hash of the key to the user's own profile, the one the vault holds now,
 * see `profileEncryptionKeyPartialHash`.
 *
 * It is what tells, without reading the vault, which contacts have yet to be handed that key:
 * each records the hash of the one they were handed last, and nearly every sync finds all of
 * them holding the current one, see `ProfileKeyDelivery`. `VaultRepository` owns it, writing
 * it whenever it reads, creates or replaces the key, and nothing else has business writing
 * here. Dropped on log out.
 *
 * Written with the fire-and-forget `putDirect`, which never throws: it comes after the vault's
 * round trip, by which time the device may have locked, and a suspending write would then
 * throw. A replacement of the key drops it first, see [clearProfileKeyPartialHash], so the write
 * after it, lost, leaves it absent rather than naming the old key, which only costs the next
 * sync a read of the vault.
 */
fun setProfileKeyPartialHash(partialHash: String) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = PROFILE_KEY_PARTIAL_HASH,
        value = partialHash,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

/** Drops the cached hash, on log out or ahead of a replacement of the key. */
fun clearProfileKeyPartialHash() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = PROFILE_KEY_PARTIAL_HASH,
    )
}

/** The cached hash, or null when this device holds none. See [getRootKey] on the type argument. */
fun getProfileKeyPartialHash(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getDirect<String?>(key = PROFILE_KEY_PARTIAL_HASH, defaultValue = null)
}
