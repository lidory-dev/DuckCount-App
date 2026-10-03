package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val KEY_PACKAGES_PUBLISHED_AT = "keyPackagesPublishedAt"

/**
 * Records when the user's key packages last went up, in epoch milliseconds, which is what
 * says when they are due again, see `KeyPackageRepository.publishIfDue`.
 *
 * Absent until the first publication of a session goes up: the end of every session clears
 * it, since the ones on the server then hold secrets the session took with it.
 *
 * Written with the fire-and-forget `putDirect`, which never throws: it comes after the
 * publication's round trip, by which time the device may have locked, and a suspending write
 * would then throw. A write that is lost only makes the next launch publish again.
 */
fun setKeyPackagesPublishedAt(publishedAt: Long) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = KEY_PACKAGES_PUBLISHED_AT,
        value = publishedAt,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearKeyPackagesPublishedAt() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = KEY_PACKAGES_PUBLISHED_AT,
    )
}

suspend fun getKeyPackagesPublishedAt(): Long? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<Long?>(KEY_PACKAGES_PUBLISHED_AT, defaultValue = null)
}
