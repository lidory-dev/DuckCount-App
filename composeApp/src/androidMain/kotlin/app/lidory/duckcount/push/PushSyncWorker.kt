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
import expense.ExpenseChanges
import expense.GroupExpenseUpdates
import expense.OneTimeExpenseSync
import group.GroupExpenseSync
import group.GroupSync
import group.GroupSyncError
import kotlinx.coroutines.CancellationException
import networking.UpdateRequirement
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "PushSyncWorker"

/**
 * Goes and reads what a push signal said had arrived, then tells the user about
 * whoever, and whatever, turned up, and what changed of what they already had.
 *
 * The signal itself says nothing about what happened, so this is where the app finds
 * out: it syncs, and compares what the device knew before with what it knows after.
 * Anything it could not finish stays on the server, and the next sync, from here or
 * from a screen opening, picks it up.
 *
 * Only a contact's expenses are told about, arriving or changing: a former contact's still
 * come in and change in the app, without a word, see [ExpenseArrivals] and [ExpenseChanges].
 * Only a contact's groups are joined, and each one is told about, see [GroupSync], as is each
 * one the user is removed from. An expense of one of the user's groups is told about whoever
 * wrote it, member of the group as they are, once it concerns the user, see [GroupExpenseUpdates].
 *
 * Also run with no signal at all, once the server's limit on messages lets through again what
 * a sync left unsent, see [PushWork.enqueueResend]: the passes that send are part of a sync. And
 * so once a member's leave from a group the user did not create is old enough for this device to
 * remove them, should the creator's device not have, see `group.DEPARTED_GRACE`: the group sync is
 * what removes them.
 */
class PushSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val contactSync: ContactSync by inject()

    private val contactArrivals: ContactArrivals by inject()

    private val contactNotifier: ContactNotifier by inject()

    private val groupSync: GroupSync by inject()

    private val groupNotifier: GroupNotifier by inject()

    private val groupExpenseSync: GroupExpenseSync by inject()

    private val groupExpenseUpdates: GroupExpenseUpdates by inject()

    private val oneTimeExpenseSync: OneTimeExpenseSync by inject()

    private val expenseArrivals: ExpenseArrivals by inject()

    private val expenseChanges: ExpenseChanges by inject()

    private val expenseNotifier: ExpenseNotifier by inject()

    private val notificationLeftovers: NotificationLeftovers by inject()

    private val pushRegistrar: UnifiedPushRegistrar by inject()

    private val updateRequirement: UpdateRequirement by inject()

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
        // A signal can outlive the session it was sent for: in flight as the user logged
        // out, or sent after the server ended the session, through a subscription nothing
        // has dropped yet. There is nothing to sync and nobody to sync it for. A stand-in
        // the signal put up while the device was locked stands for an account that is
        // gone, so it goes, with its record, or the next session would take it for one of
        // its own. So does the subscription, while this process is up to see it through.
        if (getAuthenticatedUserUuid() == null) {
            notificationLeftovers.clear()
            pushRegistrar.dropSubscription()

            return Result.success()
        }

        val contactsBefore = contactArrivals.snapshot()
        val expensesBefore = expenseArrivals.snapshot()
        val shownBefore = expenseChanges.snapshot()

        // Before the first message is read, so that a stand-in going up from here on is
        // known for one this sync may not have read.
        val standInsBefore = expenseNotifier.standInCounts()

        // Order matters. Draining the messages comes first because that is where the keys
        // to expenses arrive, and the expense sync asks creditors for the ones it finds
        // missing: run the other way round, it would ask for keys sitting unread in the
        // very response it had not opened yet. The groups come after the contacts, a group
        // being joined only from a contact: one this very sync brought in would otherwise be
        // taken for a stranger, and their group declined for good. So would one it failed to
        // bring in, which is why the groups hear whether it did.
        val contacts = contactSync.sync()
        val groups = groupSync.sync(contactsTakenIn = contacts.isTakenIn)

        // The device locked as the groups were synced, which reports what it joined and left all
        // the same, to be told about below: the next sync finds them read already. The expenses
        // could only throw now, and wait for the unlock with the rest.
        val isLocked = groups.failure == GroupSyncError.DeviceLocked
        val expenses = if (isLocked) null else oneTimeExpenseSync.sync()

        // After the groups, whose Commits give the key of every epoch the expenses are sealed in since.
        val groupExpenses = if (isLocked) null else groupExpenseSync.sync()

        // Whatever did arrive, or change, is worth showing even when a later step failed,
        // and whatever left takes what was said about it down: a contact who removed the
        // user, or deleted their account, while the notification was still up is no longer
        // a contact to announce.
        contactNotifier.notify(arrivals = contactArrivals.since(snapshot = contactsBefore))
        contactNotifier.forget(userUuids = contactArrivals.departures(snapshot = contactsBefore))

        groupNotifier.notify(arrivals = groups.joined)
        groupNotifier.notifyRemovals(removals = groups.removed)
        groupNotifier.notifyDeletions(deletions = groups.deleted)

        expenseNotifier.notify(arrivals = expenseArrivals.since(snapshot = expensesBefore))
        expenseNotifier.notifyChanges(changes = expenseChanges.since(snapshot = shownBefore))
        expenseNotifier.notifyGroupExpenses(
            updates = groupExpenseUpdates.of(changes = groupExpenses?.changes.orEmpty()),
        )

        if (isLocked) return waitForUnlock()

        if (contacts.failure != null) {
            Log.w(TAG, "doWork: could not sync the contacts: ${contacts.failure}")

            return retryOrGiveUp(standInsBefore = standInsBefore)
        }

        if (groups.failure != null) {
            Log.w(TAG, "doWork: could not sync the groups: ${groups.failure}")

            return retryOrGiveUp(standInsBefore = standInsBefore)
        }

        if (expenses is util.Result.Error) {
            Log.w(TAG, "doWork: could not sync the expenses: ${expenses.error}")

            return retryOrGiveUp(standInsBefore = standInsBefore)
        }

        groupExpenses?.failure?.let { failure ->
            Log.w(TAG, "doWork: could not sync the groups' expenses: $failure")

            return retryOrGiveUp(standInsBefore = standInsBefore)
        }

        // Only now, with everything read: a sync that failed leaves the stand-ins to the
        // retry, which may yet bring in what they announced.
        expenseNotifier.retireStandIns(countsBefore = standInsBefore)

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
     *
     * A build the server has refused is given up on at once, see [UpdateRequirement]: only an
     * update fixes that, no later moment does.
     */
    private fun retryOrGiveUp(standInsBefore: StandInCounts): Result {
        if (!updateRequirement.isRequired.value && runAttemptCount < MAX_ATTEMPTS) return Result.retry()

        // No retry is coming to take the stand-ins over either. Kept up regardless, they
        // would be up to whatever failed to say when they go, which may be nothing ever.
        expenseNotifier.retireStandIns(countsBefore = standInsBefore)

        return Result.success()
    }

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
