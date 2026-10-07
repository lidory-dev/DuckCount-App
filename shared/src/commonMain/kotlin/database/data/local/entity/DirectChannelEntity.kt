package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The MLS group this device talks to one other user through.
 *
 * A channel is not a contact. It starts as one, the group a contact request creates, but
 * it outlives the relationship in both directions: a one-time expense names beneficiaries
 * the server never required to be contacts, and a contact the user removed can still be
 * owed money and still be the only person able to hand back the key to an expense they
 * share. Keeping this apart from [ContactEntity] is what lets a channel exist for someone
 * who is not a contact, without inventing a contact row that the next back-up would carry
 * into the vault and put back in the contact list.
 *
 * Only the group's identifier lives here. Its state, and the secrets that go with it,
 * belong to `lib-duckcount` and reach the database sealed, through `MlsRecordEntity`.
 *
 * A plain class rather than a data class: its byte arrays would compare by identity.
 */
@Entity
class DirectChannelEntity(
    @PrimaryKey(autoGenerate = false) val peerUuid: String,

    /**
     * The group messages to the peer are encrypted to, or null after the user removed them
     * as a contact, which drops the groups but keeps the row, see `ContactRepository.forget`.
     * The next send builds a new one.
     */
    val mlsGroupId: ByteArray?,

    /**
     * The group [mlsGroupId] replaced, kept until another replaces it in turn, and then one
     * step more as [olderMlsGroupId]: the one this device held when it adopted a group the
     * peer is in, or the one whose Welcome went unanswered when it built a new one.
     *
     * The peer may still send in it, for three reasons. What they sent before it was
     * replaced is still on its way. When both sides rebuild at the same moment, each adopts
     * the other's group, so each receives in the one it built itself. And a peer who joined
     * a group but whose `ChannelJoined` was lost or withheld looks exactly like one who could
     * not join it.
     * Deleting it would make those messages unreadable, and an unreadable message is
     * acknowledged and gone for good, which for an expense key means the only copy of it.
     */
    val previousMlsGroupId: ByteArray?,

    /**
     * The group [previousMlsGroupId] replaced in turn, the last of the three this device
     * keeps for the peer: the next other group recorded pushes it out and deletes it, unless
     * the peer is read in it first, which adopts it again, see
     * `DirectChannelRepository.noteReadIn`.
     *
     * Kept for the same reasons, one step further. After both sides rebuilt at the same
     * moment, the group the peer sends in is this device's previous one, and a single
     * adoption more, a late Welcome or the group a contact request created, would push it
     * out while they still send there. Bounded all the same: a peer can make this device
     * join a group with every message they send, and nothing but a log-out would delete them.
     */
    val olderMlsGroupId: ByteArray?,

    /**
     * The Welcome that lets the peer join [mlsGroupId], until they demonstrably have.
     *
     * It rides along every message sent on the channel while it is set, because sends can
     * reach the server in either order and one that arrived without it would be unreadable,
     * acknowledged, and gone. It is cleared once something is read from the peer in that
     * group, which they could not have sent without joining.
     */
    val pendingWelcome: ByteArray?,

    /**
     * When [pendingWelcome] was created, so a Welcome the peer never answers is not carried
     * forever, see `DirectChannelRepository.ensureChannel`. Null when there is none.
     */
    val pendingWelcomeSince: Long?,

    /**
     * How many messages have carried [pendingWelcome]. Paired with [pendingWelcomeSince] to
     * decide a Welcome has gone unanswered long enough to build a new group, see
     * `DirectChannelRepository.ensureChannel`.
     */
    val welcomeAttachCount: Int = 0,

    /**
     * When the peer's budget of replies is whole again, see `DirectChannelRepository.claimReply`:
     * the messages this device sends them because of one they sent, which spend the user's
     * own allowance. Null until the first reply.
     *
     * Carried over whenever the row is written whole, and left alone when the peer is removed
     * as a contact, see `DirectChannelDao.clear`: either would otherwise hand them a fresh
     * budget, the first for the price of a Welcome, which they can attach to any message.
     */
    val replyBudgetFullAt: Long?,
)
