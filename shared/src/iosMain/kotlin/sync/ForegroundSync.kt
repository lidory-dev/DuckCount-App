package sync

import contact.ContactSync
import expense.OneTimeExpenseSync
import group.GroupExpenseSync
import group.GroupSync
import group.GroupSyncError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * groups' expenses. iOS receives no push yet, so this is what every screen runs as it opens, is
 * pulled, or comes back to the foreground, and what a resend booked for later runs, see
 * [ForegroundResendScheduler].
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val passes = Mutex()

    // Read and written on the main thread only, which every caller and every pass's bookkeeping
    // runs on.
    private var started = 0L
    private var finished = 0L
    private var last: ForegroundSyncPass? = null

    /** Runs a pass that starts no earlier than this call, or waits for the one that does. */
    suspend fun sync(): ForegroundSyncPass = withContext(Dispatchers.Main) {
        val asked = started

        scope.async {
            passes.withLock {
                last?.takeIf { finished > asked }?.let { return@withLock it }

                started += 1
                val number = started
                // Off the main thread: opening and checking expenses is work the screens should not wait on.
                val pass = withContext(Dispatchers.Default) { pass() }

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

    private suspend fun pass(): ForegroundSyncPass = unlessLocked("pass") {
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

        // The device locked as the groups were synced: nothing after them could read anything.
        if (groups.failure == GroupSyncError.DeviceLocked) {
            return@unlessLocked ForegroundSyncPass(isOneTimeListingRead = false, hasFailed = true)
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

        groupExpenseSync.sync().failure?.let {
            warn("pass: could not sync the groups' expenses: $it")
            hasFailed = true
        }

        ForegroundSyncPass(isOneTimeListingRead = isOneTimeListingRead, hasFailed = hasFailed)
    } ?: ForegroundSyncPass(isOneTimeListingRead = false, hasFailed = true)

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
