package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val KEY_PACKAGES_TO_PUBLISH = "keyPackagesToPublish"

/**
 * Records that the key packages the server holds for the user may be ones nobody can use,
 * which is the case after every login: a log-out or a reinstall took their secrets with it,
 * and the ones from the last login may have outlived their lifetime by now.
 * `KeyPackageRepository.publish` clears it once a fresh bundle is up.
 *
 * Suspends until the write is done rather than using the fire-and-forget `putDirect`: a
 * marker lost with the process would leave peers building groups the user can never join,
 * or none at all, until the next login.
 */
suspend fun setKeyPackagesToPublish() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.put(
        key = KEY_PACKAGES_TO_PUBLISH,
        value = true,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearKeyPackagesToPublish() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = KEY_PACKAGES_TO_PUBLISH,
    )
}

suspend fun hasKeyPackagesToPublish(): Boolean {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<Boolean>(KEY_PACKAGES_TO_PUBLISH, defaultValue = false)
}
