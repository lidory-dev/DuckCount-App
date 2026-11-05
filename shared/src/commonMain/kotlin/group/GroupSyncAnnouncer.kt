package group

/**
 * Tells the user what a group sync run outside the push sync read of their groups: those they were
 * added to and joined, those they were removed from, and those deleted, see [GroupSyncPass].
 *
 * The group sync reads each of them once, and the push sync, which tells the user about them, finds
 * nothing left to tell once another sync read them first. So a sync the user did not set off from
 * the groups they look at, such as the one a group expense written under a key the group replaced
 * since runs, see [GroupExpenseWriter], hands what it read over here.
 *
 * Bound on Android, which posts it as the push sync does, see `GroupNotifier` in `:composeApp`; a
 * no-op on iOS, which shows no group yet.
 */
interface GroupSyncAnnouncer {

    /** Tells the user about the groups [pass] joined, those it found them removed from, and those it found deleted. */
    fun announce(pass: GroupSyncPass)
}
