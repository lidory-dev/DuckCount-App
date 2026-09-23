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
     * The group messages to the peer are encrypted to, or null when this device holds
     * none.
     *
     * Null after a log-out or a reinstall, which take the group's state with them and
     * leave the contact behind: the vault brings back who the user's contacts are, never
     * the channel to them. Null too for a peer this device has heard of but never reached.
     * Either way the next send builds a new one.
     */
    val mlsGroupId: ByteArray?,

    /**
     * The group [mlsGroupId] replaced, kept until a message has been read in the new one.
     *
     * Two devices that rebuild the channel at the same moment each adopt the other's
     * group, and whichever they settle on, messages already sent in the one they left are
     * still on their way. Deleting it would make them unreadable, and an unreadable
     * message is acknowledged and gone for good, which for an expense key means the only
     * copy of it. So the old group is retired rather than deleted, and still opens what
     * arrives in it.
     */
    val previousMlsGroupId: ByteArray?,

    /**
     * The Welcome that lets the peer join [mlsGroupId], until they demonstrably have.
     *
     * It rides along every message sent on the channel while it is set, because the server
     * hands messages back in no particular order and one that arrived without it would be
     * unreadable, acknowledged, and gone. It is cleared once something is read from the
     * peer in that group, which they could not have sent without joining.
     */
    val pendingWelcome: ByteArray?,

    /**
     * When [pendingWelcome] was created, so a channel the peer never joins can be given up
     * on rather than carried forever. Null when there is no pending Welcome.
     */
    val pendingWelcomeSince: Long?,

    /**
     * How many messages have carried [pendingWelcome]. Paired with [pendingWelcomeSince]
     * to decide a Welcome is never going to be answered: it was sealed to a key package
     * whose secret the peer's log-out wiped, so no amount of resending can reach them, and
     * only building a fresh group from a fresh key package will.
     */
    val welcomeAttachCount: Int = 0,
)
