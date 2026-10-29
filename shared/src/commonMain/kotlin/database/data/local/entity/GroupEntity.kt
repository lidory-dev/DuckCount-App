package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import database.domain.Currency

/**
 * One group the user is a member of, or was, as this device holds it: its fields opened, which
 * version of them it shows, and what opens them and reaches its members.
 *
 * The server holds the same fields sealed under [groupKey], with its creator's signature over
 * them, see `group.GroupSealing`, and the group's MLS state belongs to `lib-duckcount`, which keeps
 * it sealed through `MlsRecordEntity`.
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
     * Which version of the group's information [title] and [description] are, see
     * `group.GroupVersion`: `group.FIRST_GROUP_REVISION` as the group was created, one more for each
     * edit its creator made since, see `group.GroupEditor`. A version the server lists is shown only
     * when it comes after this one, and its creator signed it, see `group.GroupSync`, so that this
     * device never goes back to a version it saw replaced. An edit is made as the next one.
     */
    val infoRevision: Long,

    /**
     * Who created the group, the one member who adds and removes members, see
     * `group.GroupMembershipChanger`, and edits its information, signed with their MLS identity,
     * see `group.GroupEditor`: the user, for a group they created, and whoever wrote the
     * Welcome, for one they joined, see `group.GroupSync`. Not the server's owner, which a deletion
     * of the creator's account hands to somebody else, who could then not commit anything the
     * members' devices would take: they take Commits, and versions of the group's information,
     * from the creator alone.
     *
     * For a group brought back after a log-out, the creator the server lists, there being no
     * Welcome left to read it from, see `group.GroupDto.creatorUuid`: its creator still, for a
     * group a deletion of their account handed over.
     */
    val ownerUuid: String,

    /**
     * The MLS group the members talk in, or null for a group brought back after a log-out, which
     * takes every MLS group with it: its fields open with [groupKey] from the user's copy the
     * server kept, see `group.GroupKeyCopies`, but nothing the members send in it can be read any
     * more.
     *
     * Kept once the user is no longer in the group, see [isLeft], although its MLS state is gone:
     * a direct message naming it is still one never to be read, see `message.DirectMessageSync`.
     */
    val mlsGroupId: ByteArray?,

    /**
     * The key the group's fields are sealed under on the server, 32 random bytes, one per group:
     * handed to the invitees inside the Welcome that lets them in, and backed up as the user's own
     * copy, which the server keeps with the group, see `group.GroupKeyCopies`. Replaced by the
     * creator once they remove somebody, see `group.GroupMembershipSender`, the members who remain
     * being handed the new one over MLS.
     */
    val groupKey: ByteArray,

    /**
     * Whether the server holds the user's copy of [groupKey], this key and not one it replaced:
     * false from the moment the key changes, see
     * [database.data.local.dao.GroupDao.updateGroupKey], until the copy of the new one went up,
     * which the sync sees to, see `group.GroupKeyCopies.putUp`.
     */
    val isBackedUp: Boolean,

    /**
     * Whether the user is no longer a member of the group: its creator removed them, or they left
     * it, see `group.GroupLeaver`. The group stays, read-only, with what the user had of it, and
     * its MLS state is deleted. The creator adding them back brings it back to life.
     */
    val isLeft: Boolean,

    /**
     * The partial hash of the key to the user's own profile they last posted into the group, which
     * its members read their name with, see `group.MemberProfileRepository`, or null when they owe
     * it theirs, posted into the group, see `group.GroupProfileKeyDelivery`.
     *
     * The creator hands every member's key over when he seats somebody, the user's included, and
     * the members take it only for somebody they hold no key for: one of them may hold an older
     * key of the user's, which only the user's own post replaces. So a group the user joins is
     * owed it, and so is one whose creator seats somebody, whatever key he relays for the user,
     * see `group.GroupSync.takeProfileKeys`. The creator's own entry is his own word to every
     * member, so a group he creates holds his key from the start, and one he seats somebody in
     * goes on holding what it held: unless his entry is missing, the vault not answering for his
     * key, which leaves the group owed it either way, see `group.GroupCreator` and
     * `group.GroupMembershipChanger`. One that is not the user's current key, as every group's is
     * once theirs rotates, is owed the current one just the same, see
     * [database.data.local.dao.GroupDao.getOwedProfileKey].
     */
    val handedProfileKeyHash: String?,
)
