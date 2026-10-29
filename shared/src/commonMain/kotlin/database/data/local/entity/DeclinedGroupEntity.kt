package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A leave owed to the server: for a group the user was invited to and that this device declined,
 * without ever joining it, or for one the user left, see `group.GroupLeaver`. Declined when its
 * Welcome came from somebody who may not add the user to a group, or could not be joined, or
 * handed over no key that opens the group the server lists, or was not waiting at all, with no copy
 * of the group's key listed with it that opens either, see `group.GroupSync`. Left from the group's settings, the
 * group then kept on this device, read-only, see [GroupEntity.isLeft], marked so in the same
 * transaction, see `GroupDao.markLeftAndOweLeave`.
 *
 * The server keeps the user a member until they leave, whatever they made of the Welcome, and
 * every message sent in the group would wait for them there ever after, never to be acknowledged.
 * So the sync leaves the group on the server, see `group.GroupApi.leave`, which drops all of it,
 * and deletes the row once the server took the leave, or said the user was never a member of a
 * group it no longer lists, so that a creator adding them back later is decided on afresh: a
 * `404` for a group it still lists is the proxy in front of it answering, the user still a
 * member, see `group.GroupLeaveAnswer.NotMember`. Until then, the group is neither asked about
 * again nor decided on again: only left again, at every sync, until the server hears it. Except a
 * group the server lists the user as the owner of, whose leave it refuses: that one stays owed,
 * neither asked about nor left, until a log-out, see `group.GroupSync.leaveDeclined`.
 *
 * Recorded before the leave is asked for, so that a device locking, or a server not answering,
 * leaves the leave owed rather than forgotten.
 *
 * Kept on this device alone, and gone with a log-out: the next session declines again every group
 * the server still lists, finding no Welcome it can open there, and leaves it then. A group the
 * user left has their copy of its key dropped from the server as they leave it for that, see
 * `group.GroupLeaver`, or the next session would bring it back as one they are still in.
 */
@Entity
class DeclinedGroupEntity(
    @PrimaryKey(autoGenerate = false) val groupUuid: String,
)
