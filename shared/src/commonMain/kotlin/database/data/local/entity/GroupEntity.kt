package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import database.domain.Currency

/**
 * One group the user is a member of, or was, as this device holds it: its fields opened, which
 * version of them it shows, and what opens them and reaches its members.
 *
 * The server holds the same fields sealed under the group's keys, with its creator's signature
 * over them, see `group.GroupSealing`, and the group's MLS state belongs to `lib-duckcount`, which
 * keeps it sealed through `MlsRecordEntity`.
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
     * `group.GroupMembershipChanger`, edits its information, signed with their MLS identity,
     * see `group.GroupEditor`, replaces a Commit nobody could apply, see `group.GroupSync`, and
     * alone may delete it, see `group.GroupDeleter`: the user, for a group they created, and whoever
     * wrote the Welcome, for one they joined. Members' devices take Commits, and versions of the
     * group's information, from the creator alone, but for any member's removal of those who
     * departed. The group goes with its creator's account, so they own it for as long as it exists.
     *
     * For a group brought back after a log-out, the owner the server lists, there being no Welcome
     * left to read it from, see `group.GroupDto.ownerUuid`.
     */
    val ownerUuid: String,

    /**
     * The MLS group the members talk in, or null for a group brought back after a log-out, which
     * takes every MLS group with it: its fields open with [groupKey] from the user's copy the
     * server kept, see `group.GroupKeyCopies`, and the keys before it the chain leads back to, but
     * nothing the members send in it can be read any more, nor any key after it derived.
     *
     * Kept once the user is no longer in the group, see [isLeft], although its MLS state is gone:
     * a direct message naming it is still one never to be read, see `message.DirectMessageSync`.
     */
    val mlsGroupId: ByteArray?,

    /**
     * The key of the MLS epoch the group is in on this device, 32 bytes, which everything written
     * in it from this device is sealed under: what MLS derives in that epoch for every member, see
     * `group.GroupKeyChain`, and backed up as the user's own copy, which the server keeps with the
     * group, see `group.GroupKeyCopies`. Replaced as each Commit takes the group to the next epoch,
     * see `group.GroupSync` and `group.GroupMembershipSender`.
     *
     * The one replaced is kept all the same, as every generation this device holds is, see
     * [GroupKeyEntity], this one included.
     */
    val groupKey: ByteArray,

    /**
     * Which generation of the group's keys [groupKey] is, see `group.GroupKeyChain`: the epoch it is
     * the key of, `group.FIRST_KEY_GENERATION` for the epoch the group's creation leaves it at. The
     * group's current one as far as this device knows, which the server takes whatever is written
     * in the group under.
     */
    val keyGeneration: Long,

    /**
     * Whether the server holds the user's copy of [groupKey], this key and not one it replaced:
     * false from the moment the key changes, see
     * [database.data.local.dao.GroupDao.takeDerivedKeys], until the copy of the new one went up,
     * which the sync sees to, see `group.GroupKeyCopies.putUp`, or true as soon as it changes for a
     * Commit of the user's, which carried their copy of the new one.
     */
    val isBackedUp: Boolean,

    /**
     * Whether the user is no longer a member of the group: its creator removed them, they left it,
     * see `group.GroupLeaver`, or its owner deleted it, see [isDeleted]. The group stays, read-only,
     * with what the user had of it, and its MLS state is deleted. The creator adding them back
     * brings it back to life.
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

    /**
     * Whether this device has taken in the group's expenses whole once, see `group.GroupExpenseSync`:
     * a pass over them that read their listing and opened every version of it, none of them failing
     * to be told or left to be read later. Until then each one it stores is new to the device and
     * none of them news to the user, see `group.GroupExpenseChange.isFirstListing`: the user just
     * joined the group, joined it again once added back, or brought it back after a log-out. One
     * the user creates starts taken in, nothing being written in it before its creation is taken,
     * see `group.GroupCreator`.
     *
     * Kept apart from where the group's feed of changes stands, see [ChangeFeedEntity]: a pass that
     * stops after the listing was kept would otherwise have the next one announce every expense of
     * the group, and a feed started over and not read again would have it announce nothing.
     */
    val hasTakenInExpenses: Boolean = false,

    /**
     * Whether this device refused a Commit every member's device refuses, read nothing after it,
     * and stays in the epoch before while the server is past it, see `group.GroupSync`: it can
     * write nothing the server would take, sealing under a key of an epoch the group has left, and
     * waits for the group's creator to replace that Commit, which their device does as it finds
     * itself in the same place. Not for the creator's own device, which replaces it, nor for a
     * group brought back after a log-out, which follows no epoch at all. Cleared once the device is
     * in step with the server again.
     */
    val isAwaitingOwner: Boolean = false,

    /**
     * Whether the server no longer holds the group, its owner having deleted it, or their account,
     * see `group.GroupSync`, which tells it from a removal: the user is out of it, see [isLeft], and
     * this is why, there being nobody left to add them back. Told on the group's screens, and read
     * nowhere else, every rule about a group the user is out of going by [isLeft]. The owner's own
     * device forgets the group instead, see `group.GroupDeleter`, unless the answer was lost and a
     * sync found it gone first.
     */
    val isDeleted: Boolean = false,
)
