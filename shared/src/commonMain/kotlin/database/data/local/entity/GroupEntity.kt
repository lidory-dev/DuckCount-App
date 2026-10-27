package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import database.domain.Currency

/**
 * One group the user is a member of, or was, as this device holds it: its fields opened, and what
 * opens them and reaches its members.
 *
 * The server holds the same fields sealed under [groupKey], see `group.GroupSealing`, and the
 * group's MLS state belongs to `lib-duckcount`, which keeps it sealed through `MlsRecordEntity`.
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class GroupEntity(
    /**
     * The group's UUID on the server, derived from its creator's UUID and a nonce their device drew,
     * see `group.GroupCreator`.
     */
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val title: String,
    val description: String,
    val currency: Currency,

    /**
     * Who created the group, the one member who adds and removes members, see
     * `group.GroupMembershipChanger`: the user, for a group they created, and whoever wrote the
     * Welcome, for one they joined, see `group.GroupSync`. Not the server's owner, which a deletion
     * of the creator's account hands to somebody else, who could then not commit anything the
     * members' devices would take: they take Commits from the creator alone.
     *
     * For a group brought back after a log-out, the owner the server lists, there being no Welcome
     * left to read it from.
     */
    val ownerUuid: String,

    /**
     * The MLS group the members talk in, or null for a group brought back after a log-out, which
     * takes every MLS group with it: its fields open with [groupKey] from the vault, but nothing
     * the members send in it can be read any more.
     *
     * Kept once the user is removed from the group, see [isLeft], although its MLS state is gone:
     * a direct message naming it is still one never to be read, see `message.DirectMessageSync`.
     */
    val mlsGroupId: ByteArray?,

    /**
     * The key the group's fields are sealed under on the server, 32 random bytes, one per group:
     * handed to the invitees inside the Welcome that lets them in, and backed up in the vault,
     * see `vault.VaultRepository.setGroupKey`. Replaced by the creator once they remove somebody,
     * see `group.GroupMembershipSender`, the members who remain being handed the new one over MLS.
     */
    val groupKey: ByteArray,

    /** Whether the vault holds [groupKey] yet. A back-up that failed is tried again by the sync. */
    val isBackedUp: Boolean,

    /**
     * Whether the creator removed the user from the group. The group stays, read-only, with what
     * the user had of it, and its MLS state is deleted. The creator adding them back brings it back
     * to life.
     */
    val isLeft: Boolean,
)
