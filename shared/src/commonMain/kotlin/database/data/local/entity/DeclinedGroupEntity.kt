package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group the user was invited to and that this device declined for good, without ever joining
 * it: its Welcome came from somebody who may not add the user to a group, or could not be joined,
 * or handed over no key that opens the group the server lists, or was not waiting at all, with no
 * key to the group in the vault either, see `group.GroupSync`.
 *
 * The server goes on listing the group, the user being a member of it there whatever they made of
 * the Welcome, and nothing lets them leave it. Without this, every sync would ask again for the
 * messages of every group the user ever declined, only to find nothing waiting. Recorded once its
 * Welcome is acknowledged, so a Welcome the server still holds is decided on again.
 *
 * Kept on this device alone, and gone with a log-out: the next session declines them all again in
 * one pass, finding no Welcome waiting, theirs long acknowledged by then.
 */
@Entity
class DeclinedGroupEntity(
    @PrimaryKey(autoGenerate = false) val groupUuid: String,
)
