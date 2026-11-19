package sync

import contact.ContactArrivals
import contact.ContactSync
import expense.ExpenseArrivals
import expense.ExpenseChanges
import expense.GroupExpenseUpdates
import expense.OneTimeExpenseSync
import group.GroupExpenseSync
import group.GroupSync
import group.GroupSyncError
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import util.Result

/** What one pass of [ForegroundSync] came to, as much as the screens and the resend timer need of it. */
class ForegroundSyncPass(
    /** Whether the one-time expenses' listing was read, which the expense list says. */
    val isOneTimeListingRead: Boolean,

    /**
     * Whether any step failed, which a pass the resend timer ran tries again for, as Android's push
     * worker retries, see [ForegroundResendScheduler]: what a failure left unsent books nothing
     * itself, but for the server's limit.
     */
    val hasFailed: Boolean,
)

/**
 * Brings everything this device shows up to date with the server, in the order Android's
 * `PushSyncWorker` does as a push wakes it: the contacts, the groups, the one-time expenses, and the
 * groups' expenses. What every screen runs as it opens, is pulled, or comes back to the foreground,
 * what a resend booked for later runs, see [ForegroundResendScheduler], and what a push runs once it
 * wakes the app, see [syncForPush].
 *
 * A pass a push asked for tells the user what it read, see [SyncAnnouncer], as the push worker does:
 * it reads what the device knew before, and compares, then says when it started and whether it read
 * everything, for the nameless notifications a push put up before it to come down, see
 * [SyncAnnouncer.passEnded]. One a screen asked for does neither, the user being in the app to see
 * what it reads, and the first after a log-in bringing back what nobody is to be told about.
 *
 * The order is what keeps it right. The contacts come first: the group sync declines for good a
 * Welcome from somebody who is no contact of the user, so it waits for whoever became one to be
 * taken in, see [GroupSync.sync]. The groups come before their expenses: a group's Commits are what
 * carry the keys its newer expenses are sealed under.
 *
 * One pass at a time, and a screen asking while one runs gets the next, which starts once it ends:
 * one that started before the screen asked may have missed what the screen expects to see. Asks
 * made during the same pass share that next one, so the three tabs coming back to the foreground at
 * once cost two passes, not three. A pass runs to its end whoever stops waiting for it: a screen
 * going away cancels its wait, not the sync other screens and the server count on.
 */
class ForegroundSync(
    private val contactSync: ContactSync,
    private val groupSync: GroupSync,
    private val oneTimeExpenseSync: OneTimeExpenseSync,
    private val groupExpenseSync: GroupExpenseSync,
    private val contactArrivals: ContactArrivals,
    private val expenseArrivals: ExpenseArrivals,
    private val expenseChanges: ExpenseChanges,
    private val groupExpenseUpdates: GroupExpenseUpdates,
    private val announcer: SyncAnnouncer,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val passes = Mutex()

    // Read and written on the main thread only, which every caller and every pass's bookkeeping
    // runs on.
    private var started = 0L
    private var finished = 0L
    private var last: ForegroundSyncPass? = null

    /** Whether the next pass to start tells the user what it read, a push having asked for it. */
    private var isNextAnnounced = false

    /** Runs a pass that starts no earlier than this call, or waits for the one that does. */
    suspend fun sync(): ForegroundSyncPass = sync(isAnnounced = false)

    /**
     * Runs a pass that starts no earlier than this call and tells the user what it read, or waits for
     * the one that does, for a push that woke the app, see [SyncAnnouncer]. Whether it read
     * everything: one that did not leaves what it missed on the server, for the next pass.
     */
    suspend fun syncForPush(): Boolean = !sync(isAnnounced = true).hasFailed

    /**
     * Has the next pass tell the user what it read, whoever asks for it, for a push that woke the app
     * on a locked device, which can read nothing: as Android's push worker waits for the unlock to
     * sync and tell, the next pass after it does, the one the app runs as the user opens it, or the
     * catch-up the app asks iOS for meanwhile, see [CatchUpScheduler], which tells anyway. Lost with
     * the process, iOS giving the app no unlock to wait for.
     */
    suspend fun announceNextPass() = withContext(Dispatchers.Main) {
        isNextAnnounced = true
    }

    private suspend fun sync(isAnnounced: Boolean): ForegroundSyncPass = withContext(Dispatchers.Main) {
        val asked = started

        // The pass that starts next is the one this waits for, whoever else it is run for.
        if (isAnnounced) isNextAnnounced = true

        scope.async {
            passes.withLock {
                last?.takeIf { finished > asked }?.let { return@withLock it }

                started += 1
                val number = started
                val announces = isNextAnnounced
                isNextAnnounced = false

                // Off the main thread: opening and checking expenses is work the screens should not wait on.
                val pass = withContext(Dispatchers.Default) { pass(announces = announces) }

                finished = number
                last = pass

                pass
            }
        }.await()
    }

    /**
     * Runs [sync], then reads the group [groupUuid]'s expenses again, as Android's group screen does
     * as it opens: without a push to wake the device, its sync has to come first, or a new epoch's
     * key would never be read.
     */
    suspend fun syncGroup(groupUuid: String) {
        sync()

        withContext(Dispatchers.Default) {
            unlessLocked("syncGroup") { groupExpenseSync.syncGroup(groupUuid = groupUuid) }?.failure?.let {
                warn("syncGroup: could not sync the group's expenses: $it")
            }
        }
    }

    /**
     * Stops the pass under way, and waits for it to, for the session's end: what it would write
     * belongs to a session that is over, and the database it writes to is about to close, see
     * `di.restartSession`. Whoever waits for it gets the cancellation, and nothing runs once this
     * returns: a sync asked for afterwards is cancelled at once.
     */
    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun pass(announces: Boolean): ForegroundSyncPass {
        val startedAt = Clock.System.now().toEpochMilliseconds()

        val pass = unlessLocked("pass") { steps(announces = announces) }
            ?: ForegroundSyncPass(isOneTimeListingRead = false, hasFailed = true)

        if (announces) announcer.passEnded(startedAtEpochMillis = startedAt, hasFailed = pass.hasFailed)

        return pass
    }

    private suspend fun steps(announces: Boolean): ForegroundSyncPass {
        // Before anything is read, for what turns up to be told apart from what was there.
        val before = if (announces) {
            Snapshots(
                contacts = contactArrivals.snapshot(),
                expenses = expenseArrivals.snapshot(),
                shown = expenseChanges.snapshot(),
            )
        } else {
            null
        }

        var hasFailed = false

        val contacts = contactSync.sync()

        contacts.failure?.let {
            warn("pass: could not sync the contacts: $it")
            hasFailed = true
        }

        val groups = groupSync.sync(contactsTakenIn = contacts.isTakenIn)

        groups.failure?.let {
            warn("pass: could not sync the groups: $it")
            hasFailed = true
        }

        // The device locked as the groups were synced: nothing after them could read anything, nor
        // could telling what the groups read, which waits for a pass with the device unlocked.
        if (groups.failure == GroupSyncError.DeviceLocked) {
            return ForegroundSyncPass(isOneTimeListingRead = false, hasFailed = true)
        }

        val isOneTimeListingRead = when (val listing = oneTimeExpenseSync.refresh()) {
            is Result.Success -> true

            is Result.Error -> {
                warn("pass: could not read the one-time expenses: ${listing.error}")
                hasFailed = true
                false
            }
        }

        // Whatever came of the listing, as the push worker's one-time sync does: the keys the user
        // owes and those missing work from what the device holds, and are what a resend for the
        // server's limit comes back for.
        (oneTimeExpenseSync.shareOwedKeys() as? Result.Error)?.let {
            warn("pass: could not hand over the keys owed: ${it.error}")
            hasFailed = true
        }

        (oneTimeExpenseSync.requestMissingKeys() as? Result.Error)?.let {
            warn("pass: could not ask for the keys missing: ${it.error}")
            hasFailed = true
        }

        val groupExpenses = groupExpenseSync.sync()

        groupExpenses.failure?.let {
            warn("pass: could not sync the groups' expenses: $it")
            hasFailed = true
        }

        // Whatever did arrive, or change, is worth telling even when a step failed, as the push
        // worker tells it.
        if (before != null) {
            announcer.announce(
                announcements = SyncAnnouncements(
                    contactArrivals = contactArrivals.since(snapshot = before.contacts),
                    contactDepartures = contactArrivals.departures(snapshot = before.contacts),
                    groupArrivals = groups.joined,
                    groupRemovals = groups.removed,
                    groupDeletions = groups.deleted,
                    expenseArrivals = expenseArrivals.since(snapshot = before.expenses),
                    expenseChanges = expenseChanges.since(snapshot = before.shown),
                    groupExpenseUpdates = groupExpenseUpdates.of(changes = groupExpenses.changes),
                ),
            )
        }

        return ForegroundSyncPass(isOneTimeListingRead = isOneTimeListingRead, hasFailed = hasFailed)
    }

    /** What the device knew as a pass a push asked for began. */
    private class Snapshots(
        val contacts: contact.ContactSnapshot,
        val expenses: expense.ExpenseSnapshot,
        val shown: expense.ShownExpenseSnapshot,
    )

    /**
     * Runs [sync], or null if the device locked as it ran: the keychain throws then, and on iOS a
     * Kotlin exception reaching Swift ends the app, where Android's push worker would retry it once
     * the device is unlocked. So would a broken invariant of the sync, which the next pass tries
     * again.
     */
    private inline fun <T> unlessLocked(caller: String, sync: () -> T): T? =
        try {
            sync()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IllegalStateException) {
            warn("$caller: the device locked, or the sync failed, as it ran: $exception")

            null
        }

    private fun warn(message: String) = println("$TAG: $message")

    private companion object {
        const val TAG = "ForegroundSync"
    }
}
