package app.lidory.duckcount.push

import android.app.KeyguardManager
import android.util.Log
import org.koin.android.ext.android.inject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import push.PushSignal
import push.WakeListStore

private const val TAG = "DuckCountPushService"

/**
 * Where the distributor hands this app what it received.
 *
 * The distributor holds the app at foreground importance for a few seconds around
 * each of these calls, which is not enough to sync with the server, so every callback
 * hands the work to [PushWork] and returns. What comes back from the distributor is
 * not a notification but a signal: a device is told that something arrived, reads it
 * from the server itself, and only then decides what to show.
 *
 * And told who sent it, which is checked before anything else: a signal from anybody who
 * may not wake the user goes no further, see [push.WakeList].
 */
class DuckCountPushService : PushService() {
    private val contactNotifier: ContactNotifier by inject()

    private val expenseNotifier: ExpenseNotifier by inject()

    private val wakeList: WakeListStore by inject()

    /**
     * The distributor has an endpoint for this device, either the first one or a
     * replacement. Either way the server has to be told before it means anything.
     */
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet

        if (keys == null) {
            // Nothing could be encrypted to it, so the server would have to send in
            // the clear, which it will not do.
            Log.w(TAG, "onNewEndpoint: the distributor gave an endpoint without keys")

            return
        }

        PushWork.enqueueRegistration(
            context = applicationContext,
            endpoint = endpoint.url,
            p256dh = keys.pubKey,
            auth = keys.auth,
        )
    }

    /**
     * Something arrived for the user.
     *
     * A message that could not be decrypted is dropped: the payload is encrypted to
     * this subscription's keys, so anything unreadable did not come from the server.
     *
     * So is a signal from anybody who may not wake the user, see [PushSignal.mayWake], with
     * nothing put up and nothing synced. The server pushes whatever anybody sends the user,
     * knowing nobody's contacts, so a stranger can still have the phone woken: this is where
     * that ends.
     */
    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) {
            Log.w(TAG, "onMessage: dropped a message that was not encrypted to this device")

            return
        }

        val signal = PushSignal.parse(payload = message.content)

        // The kind alone: who sent it is nobody else's business, a log reader's included.
        Log.d(TAG, "onMessage: ${signal.kind}")

        if (!signal.mayWake(wakeList = wakeList)) {
            Log.d(TAG, "onMessage: dropped a signal from somebody who may not wake the user")

            return
        }

        // Past who may wake the user, nothing this device stores can be read while it is
        // locked: the session, the keys and the database key are all sealed by
        // Keystore entries that require an unlocked device. So the sync waits, and what the signal itself
        // said is said now, since it is all anybody knows at this point.
        if (isDeviceLocked()) {
            contactNotifier.notifyWhileLocked(signal = signal)
            expenseNotifier.notifyWhileLocked(signal = signal)
        }

        PushWork.enqueueSync(context = applicationContext)
    }

    /** The registration is over, on the distributor's side. */
    override fun onUnregistered(instance: String) {
        Log.w(TAG, "onUnregistered: the distributor dropped the registration")

        PushWork.enqueueUnregistration(context = applicationContext)
    }

    /**
     * The distributor refused to register this app. Nothing to do here: the reason is
     * either permanent, or one the next attempt at registering runs into again.
     */
    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "onRegistrationFailed: $reason")
    }

    /** The distributor's own connection is down; it comes back on its own. */
    override fun onTempUnavailable(instance: String) {
        Log.d(TAG, "onTempUnavailable: the distributor is temporarily offline")
    }

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
}
