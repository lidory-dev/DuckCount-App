package app.lidory.duckcount.push

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.lidory.duckcount.networking.NetworkConnectivity
import authenticationUtils.getAuthenticatedUserUuid
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * Drops the push subscription a session left behind, once the app is up with nobody signed
 * in, see [UnifiedPushRegistrar.dropSubscription].
 *
 * The counterpart of [PushSetUp], on the screen the signed-out app shows. That is where the
 * process a log-out or an expiry relaunches into lands, and unlike the one that ended the
 * session, it lives on long enough for the distributor to act on being told.
 */
@Composable
fun PushTearDown() {
    val registrar = koinInject<UnifiedPushRegistrar>()
    val networkConnectivity = koinInject<NetworkConnectivity>()

    LaunchedEffect(Unit) {
        // Not while a registration is being finished, whose account is signed in already.
        if (getAuthenticatedUserUuid() != null) return@LaunchedEffect

        // A drop made offline is lost for good, see [UnifiedPushRegistrar.dropSubscription],
        // while one put off keeps the subscription for the next try. Signing in needs the
        // network too, and takes far longer than this takes to notice it is back.
        while (!networkConnectivity.isOnline()) delay(ONLINE_POLL_MILLISECONDS)

        registrar.dropSubscription()
    }
}

/** How often a device that is offline is looked at again, for as long as it is signed out. */
private const val ONLINE_POLL_MILLISECONDS = 1_000L
