package group

/**
 * Tells nothing: iOS has no notifications yet, and the user chose to hear of a removal or a deletion
 * from the group itself until it does, which the list marks and the group's screens explain. The seam
 * a sync's joins, removals and deletions are told through once iOS posts notifications, see
 * [GroupSyncAnnouncer].
 */
internal object NoGroupSyncAnnouncer : GroupSyncAnnouncer {
    override fun announce(pass: GroupSyncPass) = Unit
}
