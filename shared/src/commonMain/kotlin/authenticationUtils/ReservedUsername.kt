package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import kotlinx.coroutines.flow.Flow
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val DISCOVERY_USERNAME = "discoveryUsername"

/**
 * Saves the discovery username the user reserved, in its normalized form.
 *
 * The server holds only the digest and exposes no way to read it back, so this is the
 * only record that the user is discoverable at all. Set when a reservation succeeds,
 * and cleared on log out.
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

fun clearDiscoveryUsername() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = DISCOVERY_USERNAME,
    )
}

fun getDiscoveryUsername(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getDirect(key = DISCOVERY_USERNAME, defaultValue = null)
}

/** Emits the discovery username, and again whenever it changes. */
fun getDiscoveryUsernameFlow(): Flow<String?> {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getFlow(key = DISCOVERY_USERNAME, defaultValue = null)
}
