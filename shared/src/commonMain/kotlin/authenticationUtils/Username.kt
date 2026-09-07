package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val USERNAME = "username"

/**
 * Saves the authenticated user's username.
 *
 * Set when registration or login finishes.
 *
 * TODO: This information is already in DB, remove it from KSafe.
 */
fun setAuthenticatedUserUsername(username: String) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = USERNAME,
        value = username,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearAuthenticatedUserUsername() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = USERNAME,
    )
}

fun getAuthenticatedUserUsername(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getDirect(key = USERNAME, defaultValue = null)
}
