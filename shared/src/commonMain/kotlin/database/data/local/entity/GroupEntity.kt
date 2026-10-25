package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import database.domain.Currency

/**
 * One group the user is a member of, as this device holds it: its fields opened, and what opens
 * them and reaches its members.
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

    /** Who the server holds as owning the group: its creator, until their account is deleted. */
    val ownerUuid: String,

    /**
     * The MLS group the members talk in, or null for a group brought back after a log-out, which
     * takes every MLS group with it: its fields open with [groupKey] from the vault, but nothing
     * the members send in it can be read any more.
     */
    val mlsGroupId: ByteArray?,

    /**
     * The key the group's fields are sealed under on the server, 32 random bytes, one per group:
     * handed to the invitees inside the Welcome that lets them in, and backed up in the vault,
     * see `vault.VaultRepository.setGroupKey`.
     */
    val groupKey: ByteArray,

    /** Whether the vault holds [groupKey] yet. A back-up that failed is tried again by the sync. */
    val isBackedUp: Boolean,
)
