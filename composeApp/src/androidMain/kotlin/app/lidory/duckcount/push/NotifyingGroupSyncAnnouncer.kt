package app.lidory.duckcount.push

import group.GroupSyncAnnouncer
import group.GroupSyncPass

/** Posts what a group sync the shared layer ran read, as the push sync does, see [GroupNotifier]. */
class NotifyingGroupSyncAnnouncer(
    private val groupNotifier: GroupNotifier,
) : GroupSyncAnnouncer {

    override fun announce(pass: GroupSyncPass) {
        groupNotifier.notify(arrivals = pass.joined)
        groupNotifier.notifyRemovals(removals = pass.removed)
    }
}
