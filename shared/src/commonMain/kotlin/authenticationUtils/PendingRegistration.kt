package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

/**
 * A registration that succeeded server-side (tokens issued) but whose local profile step
 * (the "set up profile" screen) hasn't been completed yet. Persisting it lets the app resume
 * that step after the process is killed instead of forcing the user to log in again.
 */
@Serializable
data class PendingRegistration(
    val uuid: String,
    val username: String,
    val hasPasswordBeenChecked: Boolean,
)

private const val PENDING_REGISTRATION_KEY = "pendingRegistration"

private val pendingRegistrationJson = Json { ignoreUnknownKeys = true }

fun setPendingRegistration(pendingRegistration: PendingRegistration) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = PENDING_REGISTRATION_KEY,
        value = pendingRegistrationJson.encodeToString(pendingRegistration),
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

suspend fun getPendingRegistration(): PendingRegistration? {
    val vault: KSafe = getKoin().get(named("vault"))

    val stored = vault.get(PENDING_REGISTRATION_KEY, defaultValue = "")
    if (stored.isBlank()) return null

    return runCatching {
        pendingRegistrationJson.decodeFromString<PendingRegistration>(stored)
    }.getOrNull()
}

fun clearPendingRegistration() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = PENDING_REGISTRATION_KEY,
        value = "",
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}
