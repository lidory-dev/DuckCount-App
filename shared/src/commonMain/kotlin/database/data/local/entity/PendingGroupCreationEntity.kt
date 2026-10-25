package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group the user created that the server has not been heard to take yet: exactly what goes
 * out as `POST /groups`, kept until the server answers it.
 *
 * Written with the group, in one transaction, before the request goes out, and sent again by
 * every later sync until the server answers, see `group.GroupCreationSender`. The group is the
 * creator's from the moment it is saved, and an answer that never came back may well have been
 * the server storing it: the members may be joining it already, from the Welcome it holds, and
 * only the same request again can find that out without creating the group twice. So it is kept
 * whole rather than rebuilt: another nonce would derive another group's UUID, and another
 * Welcome would open another MLS group.
 */
@Entity
class PendingGroupCreationEntity(
    @PrimaryKey(autoGenerate = false) val groupUuid: String,

    /** The body of the request, serialized, see `group.CreateGroupBody`. */
    val body: String,
)
