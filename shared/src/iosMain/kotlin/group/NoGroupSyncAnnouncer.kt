package group

/**
 * Tells nothing: iOS shows no group yet, so nothing it syncs of them is the user's to hear of. The
 * seam a sync's groups are told through once it does, see [GroupSyncAnnouncer].
 */
internal object NoGroupSyncAnnouncer : GroupSyncAnnouncer {
    override fun announce(pass: GroupSyncPass) = Unit
}
