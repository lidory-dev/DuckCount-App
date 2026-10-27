package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A change to the members of a group the user created that the server has not been heard to take
 * yet, or whose taking this device has yet to act on: exactly what goes out, kept until then, see
 * `group.GroupMembershipSender`.
 *
 * One per group at most: the library keeps one Commit pending per group, and the change after a
 * removal, its key rotation, waits for the removal to be taken. Written before the request first
 * goes out, and sent again by every later sync until the server answers, for the reason a group
 * creation is, see [PendingGroupCreationEntity]: an answer that never came back may well have been
 * the server storing the change, which the same request again finds out without making it twice,
 * its nonce naming the change, while another Commit would be one the members never get.
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity
class PendingMembershipChangeEntity(
    @PrimaryKey(autoGenerate = false) val groupUuid: String,

    val kind: MembershipChangeKind,

    /**
     * The body of the request, serialized: `group.AddGroupMembersBody`,
     * `group.RemoveGroupMembersBody` or `group.RotateGroupKeyBody`, as [kind] says.
     */
    val body: String,

    /**
     * For an addition or a removal, the epoch the group was in when its Commit was built. The
     * group past it is the Commit applied, whether or not this row was dropped after, see
     * `group.GroupMembershipSender`. Null for a key rotation, which builds none.
     */
    val commitEpoch: Long?,

    /**
     * For a key rotation, the key it hands the members, which becomes the group's once the server
     * takes it. Null otherwise.
     */
    val groupKey: ByteArray?,
)

/** What a [PendingMembershipChangeEntity] changes. */
enum class MembershipChangeKind {
    /** `POST /groups/{group_uuid}/members`. */
    ADDITION,

    /** `DELETE /groups/{group_uuid}/members`, after which the group's key is rotated. */
    REMOVAL,

    /** `PUT /groups/{group_uuid}/key`, which hands the members who remain after a removal a new key. */
    KEY_ROTATION,
}
