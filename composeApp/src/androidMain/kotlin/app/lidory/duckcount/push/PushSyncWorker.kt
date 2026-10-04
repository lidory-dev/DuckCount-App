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
import expense.ExpenseArrivals
import expense.OneTimeExpenseSync
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "PushSyncWorker"

/**
 * Goes and reads what a push signal said had arrived, then tells the user about
 * whoever, and whatever, turned up.
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

    private val oneTimeExpenseSync: OneTimeExpenseSync by inject()

    private val expenseArrivals: ExpenseArrivals by inject()

    private val expenseNotifier: ExpenseNotifier by inject()

    private val notificationLeftovers: NotificationLeftovers by inject()

    override suspend fun doWork(): Result {
        // Nothing this device stores can be read while it is locked, so the sync waits
        // for an unlock. Telling the user what the signal said is
        // [DuckCountPushService]'s job, which needs no storage to do it.
        if (isDeviceLocked()) return waitForUnlock()

        return try {
            sync()
        } catch (exception: CancellationException) {
            // WorkManager stopping the worker, which the catch below would otherwise take for
            // a locked device, a CancellationException being an IllegalStateException on the
            // JVM. WorkManager reschedules stopped work itself, unless it was stopped for good.
            throw exception
        } catch (exception: IllegalStateException) {
            // The device locked while this was running, so the same applies.
            Log.w(TAG, "doWork: could not read this device's storage", exception)

            waitForUnlock()
        }
    }

    private suspend fun sync(): Result {
        // A signal can outlive the session it was sent for: the user logged out while
        // it was in flight, or the server ended the session, which leaves it pushing to
        // this device all the same. There is nothing to sync and nobody to sync it for.
        // A stand-in the signal put up while the device was locked stands for an account
        // that is gone, so it goes, with its record, or the next session would take it
        // for one of its own.
        if (getAuthenticatedUserUuid() == null) {
            notificationLeftovers.clear()

            return Result.success()
        }

        val contactsBefore = contactArrivals.snapshot()
        val expensesBefore = expenseArrivals.snapshot()

        // Order matters. Draining the messages comes first because that is where the keys
        // to expenses arrive, and the expense sync asks creditors for the ones it finds
        // missing: run the other way round, it would ask for keys sitting unread in the
        // very response it had not opened yet.
        val contacts = contactSync.sync()
        val expenses = oneTimeExpenseSync.sync()

        // Whatever did arrive is worth showing even when a later step failed, and
        // whatever left takes what was said about it down: a contact who removed the
        // user while the notification was still up is no longer a contact to announce.
        contactNotifier.notify(arrivals = contactArrivals.since(snapshot = contactsBefore))
        contactNotifier.forget(userUuids = contactArrivals.departures(snapshot = contactsBefore))

        expenseNotifier.notify(arrivals = expenseArrivals.since(snapshot = expensesBefore))

        if (contacts is util.Result.Error) {
            Log.w(TAG, "doWork: could not sync the contacts: ${contacts.error}")

            return retryOrGiveUp()
        }

        if (expenses is util.Result.Error) {
            Log.w(TAG, "doWork: could not sync the expenses: ${expenses.error}")

            return retryOrGiveUp()
        }

        return Result.success()
    }

    /**
     * Waits for the device to be unlocked, however long that takes.
     *
     * Deliberately not counted against [MAX_ATTEMPTS]. A locked device is not a
     * failure, it is a moment that has not come yet, and it is also the *normal* state
     * when a push arrives: the phone is in a pocket. Spending the attempt budget on it
     * meant a phone locked for more than a couple of minutes gave up on the sync
     * entirely, and since [PushWork] keeps the existing request rather than replacing
     * it, the pushes that arrived in the meantime were dropped on the floor too. What
     * the user was left with was the contentless notification the signal alone allows,
     * and nothing ever came along to say who it was from.
     *
     * It asks for a fresh look on a fixed interval rather than returning
     * [Result.retry], whose backoff doubles every time and would have the user waiting
     * minutes with the phone already in their hand. See
     * [PushWork.enqueueSyncAfterUnlockWait], which also records why there is no unlock
     * event to wait on instead.
     */
    private fun waitForUnlock(): Result {
        PushWork.enqueueSyncAfterUnlockWait(context = applicationContext)

        return Result.success()
    }

    /**
     * Retrying is for the failures a later moment fixes: a dead network, a server
     * having a bad minute. Past a few attempts the app stops paying for it, since the
     * screens sync when they open and nothing is lost by giving up here.
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
