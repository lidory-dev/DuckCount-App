package sync

import contact.ContactArrival
import expense.ExpenseArrival
import expense.ExpenseChange
import expense.GroupExpenseUpdate
import group.GroupArrival
import group.GroupDeletion
import group.GroupRemoval
import group.GroupSyncAnnouncer
import group.GroupSyncPass

/**
 * What one sync read that the user is to be told about, as Android's `PushSyncWorker` tells it: who
 * turned up and who left of the contacts, the groups joined, left behind and deleted, the one-time
 * expenses arriving and changing, and the groups' expenses that concern the user.
 */
class SyncAnnouncements(
    val contactArrivals: List<ContactArrival>,

    /** Contacts who removed the user or deleted their account, whose acceptance is no longer news. */
    val contactDepartures: List<String>,
    val groupArrivals: List<GroupArrival>,
    val groupRemovals: List<GroupRemoval>,
    val groupDeletions: List<GroupDeletion>,
    val expenseArrivals: List<ExpenseArrival>,
    val expenseChanges: List<ExpenseChange>,
    val groupExpenseUpdates: List<GroupExpenseUpdate>,
) {
    internal companion object {
        /** What a sync of the groups alone read, see [AnnouncingGroupSyncAnnouncer]. */
        fun of(pass: GroupSyncPass) = SyncAnnouncements(
            contactArrivals = emptyList(),
            contactDepartures = emptyList(),
            groupArrivals = pass.joined,
            groupRemovals = pass.removed,
            groupDeletions = pass.deleted,
            expenseArrivals = emptyList(),
            expenseChanges = emptyList(),
            groupExpenseUpdates = emptyList(),
        )
    }
}

/**
 * Where the app posts what a sync read, as notifications, implemented in Swift: the texts are the
 * app's String Catalog's, and the notification center the app's.
 *
 * Called off the main thread, as a sync ends.
 */
interface SyncAnnouncer {

    /** Tells the user what a sync read, see [SyncAnnouncements]. */
    fun announce(announcements: SyncAnnouncements)

    /**
     * A pass of [ForegroundSync] a push asked for, which started at [startedAtEpochMillis], ended,
     * having read everything unless [hasFailed]: whatever a nameless notification a push put up before
     * it started announced is in by then, or never was, see the app's `Notifier`.
     */
    fun passEnded(startedAtEpochMillis: Long, hasFailed: Boolean)
}

/**
 * Tells the user about the groups a sync the shared layer ran on its own read, as Android's
 * `NotifyingGroupSyncAnnouncer` does: the one each write to a group runs after it, see
 * [GroupSyncAnnouncer].
 */
internal class AnnouncingGroupSyncAnnouncer(private val announcer: SyncAnnouncer) : GroupSyncAnnouncer {
    override fun announce(pass: GroupSyncPass) = announcer.announce(announcements = SyncAnnouncements.of(pass))
}
