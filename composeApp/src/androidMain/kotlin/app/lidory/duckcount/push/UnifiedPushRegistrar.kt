package app.lidory.duckcount.push

import android.app.Activity
import android.content.Context
import android.util.Log
import org.unifiedpush.android.connector.UnifiedPush
import push.PushRegistrar
import push.PushRepository
import util.Result

private const val TAG = "UnifiedPushRegistrar"

/**
 * The Android half of push: UnifiedPush, so that a phone without Google services is
 * as reachable as one with them.
 *
 * The component holding the connection to a push server is a *distributor*, a
 * separate app the user chose, and the endpoint it hands out is a Web Push resource.
 * A phone with none falls back to the embedded distributor bundled with the app,
 * which registers against Google's Web Push endpoint with the same VAPID key and
 * needs no Firebase project of its own.
 *
 * Nothing here talks to the server: registering only asks the distributor, which
 * answers later, on its own schedule, in [DuckCountPushService.onNewEndpoint].
 */
class UnifiedPushRegistrar(
    private val context: Context,
    private val pushRepository: PushRepository,
) : PushRegistrar {

    /**
     * Makes sure this device has an endpoint and that the server knows it.
     *
     * Takes the activity because choosing a distributor is the system's dialog to
     * show: a phone with several installed and no default has to ask. Safe to call on
     * every launch, which is what it is for (an endpoint can change on its own, and
     * a phone that had no distributor may have one now).
     */
    suspend fun ensureRegistered(activity: Activity) {
        // The distributor binds the subscription it creates to this key, and the push
        // service later refuses anything signed with another, so it has to be in hand
        // before registering rather than after.
        val vapid = when (val parameters = pushRepository.vapidPublicKey()) {
            is Result.Success -> parameters.data

            is Result.Error -> {
                Log.w(TAG, "ensureRegistered: could not read the push parameters: ${parameters.error}")

                return
            }
        }

        // Uses the distributor already chosen, else the phone's default, else the
        // embedded one. `false` means the phone has nothing at all to push with, which
        // is not something the app can fix.
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { found ->
            if (!found) {
                Log.w(TAG, "ensureRegistered: this device has no distributor")

                return@tryUseCurrentOrDefaultDistributor
            }

            try {
                UnifiedPush.register(context = context, vapid = vapid)
            } catch (exception: UnifiedPush.VapidNotValidException) {
                Log.e(TAG, "ensureRegistered: the server's VAPID key was refused", exception)
            }
        }
    }

    override suspend fun tearDown() {
        // The server first, while the tokens that call needs still exist.
        pushRepository.unregister().let { outcome ->
            if (outcome is Result.Error) {
                Log.w(TAG, "tearDown: could not tell the server: ${outcome.error}")
            }
        }

        // The distributor keeps being this device's distributor, it just stops holding
        // a subscription for this app, so the next login does not ask the user again.
        UnifiedPush.unregister(context = context)
    }
}
