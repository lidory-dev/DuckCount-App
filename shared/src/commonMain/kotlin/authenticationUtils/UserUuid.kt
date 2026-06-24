package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val USER_UUID = "userUuid"

/**
 * Saves the authenticated user's UUID.
 *
 * Set when registration or login finishes.
 */
fun setAuthenticatedUserUuid(uuid: String) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = USER_UUID,
        value = uuid,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearAuthenticatedUserUuid() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = USER_UUID,
    )
}

fun getAuthenticatedUserUuid(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getDirect(key = USER_UUID, defaultValue = null)
}
