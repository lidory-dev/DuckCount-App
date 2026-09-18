package app.lidory.duckcount.push

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactArrivals
import contact.ContactSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "PushSyncWorker"

/**
 * Goes and reads what a push signal said had arrived, then tells the user about
 * whoever turned up.
 *
 * The signal itself says nothing about what happened, so this is where the app finds
 * out: it syncs, and compares what the device knew before with what it knows after.
 * Anything it could not finish stays on the server, and the next sync, from here or
 * from a screen opening, picks it up.
 */
class PushSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val contactSync: ContactSync by inject()

    private val contactArrivals: ContactArrivals by inject()

    private val contactNotifier: ContactNotifier by inject()

    override suspend fun doWork(): Result {
        // Nothing this device stores can be read while it is locked, so the sync waits
        // for an unlock. Telling the user what the signal said is
        // [DuckCountPushService]'s job, which needs no storage to do it.
        if (isDeviceLocked()) return retryOrGiveUp()

        return try {
            sync()
        } catch (exception: IllegalStateException) {
            // The device locked while this was running, so the same applies.
            Log.w(TAG, "doWork: could not read this device's storage", exception)

            retryOrGiveUp()
        }
    }

    private suspend fun sync(): Result {
        // A signal can outlive the session it was sent for: the user logged out while
        // it was in flight, or the server ended the session. There is nothing to sync
        // and nobody to sync it for.
        if (getAuthenticatedUserUuid() == null) return Result.success()

        val before = contactArrivals.snapshot()

        val outcome = contactSync.sync()

        // Whatever did arrive is worth showing even when a later step failed, and
        // whatever left takes what was said about it down: a contact who removed the
        // user while the notification was still up is no longer a contact to announce.
        contactNotifier.notify(arrivals = contactArrivals.since(snapshot = before))
        contactNotifier.forget(userUuids = contactArrivals.departures(snapshot = before))

        if (outcome is util.Result.Error) {
            Log.w(TAG, "doWork: could not sync the contacts: ${outcome.error}")

            return retryOrGiveUp()
        }

        return Result.success()
    }

    /**
     * Retrying is for the failures a later moment fixes: a dead network, a locked
     * device. Past a few attempts the app stops paying for it, since the screens sync
     * when they open and nothing is lost by giving up here.
     */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    private fun isDeviceLocked(): Boolean =
        applicationContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true


    /**
     * Below Android 12 an expedited worker runs as a foreground service, and a
     * foreground service has to show a notification.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = contactNotifier.syncForegroundInfo()
}

/** How many times a failing sync is retried before it is left to the next screen. */
private const val MAX_ATTEMPTS = 3
