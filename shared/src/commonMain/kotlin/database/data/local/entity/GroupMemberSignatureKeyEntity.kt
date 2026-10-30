package database.data.local.entity

import androidx.room.Entity

/**
 * The MLS signature key a member of a group signs with, as the group's tree held it when this
 * device last read it there: the key whatever they write in the group is checked against when this
 * device holds no better one for them, the user's own or the one pinned for a contact.
 *
 * Recorded wherever the group's tree is read as it changes: as the user joins the group, and with
 * every Commit of its creator's read since, see `group.GroupSync`, or, on the creator's own device,
 * applied, see `group.GroupMembershipSender`. Kept once the member leaves the group, or is removed
 * from it: what they wrote while in it stays in it, and is checked against this then. Gone with the
 * group only, see [database.data.local.dao.GroupDao.deleteRefusedCreation], and at log-out.
 *
 * A member's key changes only with their MLS identity, which every group they are in holds the one
 * of, so the latest read replaces whatever was held for them. One read in a group stands in for
 * none read in another only when both have the same creator, whose word the tree is, see
 * [database.data.local.dao.GroupDao.getMemberSignatureKey].
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity(primaryKeys = ["groupUuid", "userUuid"])
class GroupMemberSignatureKeyEntity(
    val groupUuid: String,

    val userUuid: String,

    /** The raw MLS signature public key of their leaf. */
    val signatureKey: ByteArray,
)
