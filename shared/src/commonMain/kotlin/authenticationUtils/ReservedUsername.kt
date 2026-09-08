package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import kotlinx.coroutines.flow.Flow
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val DISCOVERY_USERNAME = "discoveryUsername"

/**
 * Caches the discovery username the user reserved, in its normalized form.
 *
 * The handle itself lives in the vault, which is what makes it survive a reinstall,
 * and this is the local copy the screens that show it read without a round trip. It
 * is written when a reservation succeeds and when a login restores the vault's copy,
 * and dropped on log out. `DiscoveryUsernameRepository` owns both, nothing else has
 * business writing here.
 */
fun setDiscoveryUsername(username: String) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = DISCOVERY_USERNAME,
        value = username,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

/** Drops the local copy, on log out or when the vault turns out to hold none. */
fun clearDiscoveryUsername() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = DISCOVERY_USERNAME,
    )
}

/** The cached discovery username, or null when this device has not been told of one. */
fun getDiscoveryUsername(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getDirect(key = DISCOVERY_USERNAME, defaultValue = null)
}

/** Emits the cached discovery username, and again whenever it changes. */
fun getDiscoveryUsernameFlow(): Flow<String?> {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getFlow(key = DISCOVERY_USERNAME, defaultValue = null)
}
