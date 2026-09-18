package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val REGISTERED_PUSH_SUBSCRIPTION = "registeredPushSubscription"

/**
 * Remembers the push subscription this device last registered with the server.
 *
 * Only ever compared, never taken apart: what it holds is whatever
 * `PushRepository` considers one subscription, so that a client which finds itself
 * with the same endpoint it already registered says nothing to the server. A device
 * asks its distributor for an endpoint every time the app starts, and almost every
 * time it is handed the same one back.
 */
fun setRegisteredPushSubscription(subscription: String) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = REGISTERED_PUSH_SUBSCRIPTION,
        value = subscription,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

/** Forgets it, on log out or when the server no longer holds the subscription. */
fun clearRegisteredPushSubscription() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = REGISTERED_PUSH_SUBSCRIPTION,
    )
}

/** What this device registered, or null when it has registered nothing. */
fun getRegisteredPushSubscription(): String? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getDirect(key = REGISTERED_PUSH_SUBSCRIPTION, defaultValue = null)
}
