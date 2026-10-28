package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A contact request the user sent, waiting for its answer.
 *
 * The answer is the card the recipient sends in the group the request created, and a card is
 * only taken as one when it comes from [recipientUserUuid] in [mlsGroupId]: anybody else this
 * device can read could otherwise make themselves a contact by sending one, in whatever group
 * the two of them share. The server lists a request only to its recipient, and does not keep
 * the group it created, so this is the device's only record of either.
 *
 * Only the answer makes the group the channel to them, see `ContactRequestRepository.receiveAnswer`.
 * Creating it pinned the recipient, so whatever else they send in it is read, answered or not,
 * and taken as from somebody who is not a contact, see `DirectChannelRepository.noteReadIn`.
 *
 * Every request to somebody goes once they become a contact, whichever way: the server drops
 * them all as soon as either side accepts one, and a row left behind would let them come back
 * by the same route after the user removed them.
 *
 * Only the row goes then, never its group. When two requests crossed, each side can adopt a
 * different one as the channel, one side's acceptance having been completed after the other's
 * landed, see [ContactRequestEntity.isAcceptanceSent], and each reads what the other sends only
 * through the group of the request it sent itself, until reading it brings the two together,
 * see `DirectChannelRepository.noteReadIn`. Deleted, every message would be unreadable, and an
 * adopted channel carries no Welcome that could ever rebuild it.
 *
 * A row is kept when sending its request ended in doubt, a timeout for instance, since the
 * request may be waiting for the recipient all the same. One that never reached the server
 * stays harmless: nobody but the recipient can answer in its group, and they never got the
 * Welcome that lets them in. Nor does it count as a request out to them, see [isStored]: they
 * can be asked again, and the server settles it, refusing the new request as one already
 * pending or storing it.
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity
class OutgoingContactRequestEntity(
    /** The group the request created, which the answer arrives in. */
    @PrimaryKey(autoGenerate = false) val mlsGroupId: ByteArray,

    val recipientUserUuid: String,

    /**
     * The partial hash of the key to the user's own profile the request carried, which is
     * what the recipient was handed once they answer it, see
     * [ContactEntity.handedProfileKeyHash]. A removal since may have rotated it, and the
     * recipient is then handed the new one once a contact. Until then they get nothing: the
     * server keeps the version under the key they hold, which the user's edits rewrite only
     * while it is the current key, so they read the profile as it stood when it was replaced.
     */
    val handedProfileKeyHash: String,

    /**
     * Whether the server is known to hold a request of the user's to [recipientUserUuid]: this
     * one, its sending answered with a success, or one of the others to them, when a later
     * request was refused as one already pending, which does not say which. Only then does the
     * user have a request out to them, see
     * [ContactRelation.REQUEST_SENT][contact.ContactRelation.REQUEST_SENT]: one whose sending
     * ended in doubt may never have reached the server, and nothing would ever settle it.
     *
     * Every row still matches its answer, stored or not.
     */
    val isStored: Boolean = false,
)
