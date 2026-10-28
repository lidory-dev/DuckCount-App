package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group the user was invited to and that this device declined, without ever joining it: its
 * Welcome came from somebody who may not add the user to a group, or could not be joined, or
 * handed over no key that opens the group the server lists, or was not waiting at all, with no key
 * to the group in the vault either, see `group.GroupSync`.
 *
 * A leave owed: the server keeps the user a member whatever they made of the Welcome, and every
 * message sent in the group would wait for them there ever after, never to be acknowledged. So
 * the sync leaves the group on the server, see `group.GroupApi.leave`, which drops all of it, and
 * deletes the row once the server took the leave, or said the user was never a member, so that a
 * creator adding them back later is decided on afresh. Until then, the group is neither asked
 * about again nor decided on again: only left again, at every sync, until the server hears it.
 * Except a group the server lists the user as the owner of, whose leave it refuses: that one stays
 * declined, neither asked about nor left, until a log-out, see `group.GroupSync.leaveDeclined`.
 *
 * Recorded before the leave is asked for, so that a device locking, or a server not answering,
 * leaves the leave owed rather than forgotten.
 *
 * Kept on this device alone, and gone with a log-out: the next session declines again every group
 * the server still lists, finding no Welcome it can open there, and leaves it then.
 */
@Entity
class DeclinedGroupEntity(
    @PrimaryKey(autoGenerate = false) val groupUuid: String,
)
