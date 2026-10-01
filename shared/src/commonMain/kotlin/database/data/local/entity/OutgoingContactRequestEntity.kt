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
 * Welcome that lets them in.
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity
class OutgoingContactRequestEntity(
    /** The group the request created, which the answer arrives in. */
    @PrimaryKey(autoGenerate = false) val mlsGroupId: ByteArray,

    val recipientUserUuid: String,
)
