package database.data.local.entity

import androidx.room.Entity

/**
 * A Commit of the user's to a group that the server has not been heard to take yet, or whose
 * taking this device has yet to act on: exactly what goes out, kept until then, see
 * `group.GroupMembershipSender`. Any member may have one: the creator's additions, removals and
 * replacement of a Commit nobody could apply, any member's removal of those who departed, and any
 * member's rejoin of a group a log-out took the MLS state of, see `group.GroupRejoiner`.
 *
 * One per group at most waiting on the server: the library keeps one Commit pending per group.
 * Written before the request first goes out, and sent again by every later sync until the server
 * answers, for the reason a group creation is, see [PendingGroupCreationEntity]: an answer that
 * never came back may well have been the server storing the change, which the same request again
 * finds out without making it twice, its nonce naming the change, while another Commit would be
 * one the members never get. And one per group at most to be made again, see [isOutdated], kept
 * beside it.
 */
@Entity(primaryKeys = ["groupUuid", "isOutdated"])
data class PendingMembershipChangeEntity(
    val groupUuid: String,

    val kind: MembershipChangeKind,

    /**
     * The body of the request, serialized: `group.AddGroupMembersBody`,
     * `group.RemoveGroupMembersBody`, `group.RemoveDepartedMembersBody`,
     * `group.ReplaceGroupCommitBody` or `group.RejoinGroupBody`, as [kind] says.
     */
    val body: String,

    /**
     * The epoch the group was in when its Commit was built. The group past it is the Commit
     * applied, whether or not this row was dropped after, see `group.GroupMembershipSender`. For a
     * rejoin, the epoch of the GroupInfo it was built from, which this device never held: its
     * group is past it from the start, see [MembershipChangeKind.REJOIN].
     */
    val commitEpoch: Long,

    /**
     * Whether the server refused the Commit for its epoch as it was sent again, another member's
     * Commit having taken the group past it as this change waited: an addition or a removal the
     * user asked for, which no sync decides on again of itself, as it does its own. Its Commit is
     * discarded, nothing is sent again, and the group sync makes the change anew for whoever it
     * names once it read that Commit, see `group.GroupMembershipChanger.remakeOutdatedUnderGroupsLock`.
     * The group still has a change pending meanwhile, which no other is made over, see
     * [database.data.local.dao.GroupDao.hasPendingChange], but none waiting on the server. Bar the
     * creator's replacement of a Commit every member's device refused, made ahead of it, see
     * `group.GroupMembershipChanger.replaceUnderGroupsLock`: the group is in no epoch the server
     * takes a Commit of until that one is taken, this one included.
     */
    val isOutdated: Boolean = false,
)

/** What a [PendingMembershipChangeEntity] changes. */
enum class MembershipChangeKind {
    /** `POST /groups/{group_uuid}/members`. */
    ADDITION,

    /** `DELETE /groups/{group_uuid}/members`. */
    REMOVAL,

    /** `DELETE /groups/{group_uuid}/departed`, which any member sends. */
    DEPARTED_REMOVAL,

    /** `POST /groups/{group_uuid}/commits/replacement`, which the creator sends. */
    REPLACEMENT,

    /**
     * `POST /groups/{group_uuid}/commits/rejoin`, which any member sends of a group a log-out took
     * the MLS state of, see `group.GroupRejoiner`. Its Commit is applied already, mls-rs building
     * a Commit from outside a group so, and pending nowhere: what became of it, its nonce tells.
     */
    REJOIN,
}
