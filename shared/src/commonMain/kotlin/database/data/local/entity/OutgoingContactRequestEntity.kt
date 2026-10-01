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
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity
class OutgoingContactRequestEntity(
    /** The group the request created, which the answer arrives in. */
    @PrimaryKey(autoGenerate = false) val mlsGroupId: ByteArray,

    val recipientUserUuid: String,
)
