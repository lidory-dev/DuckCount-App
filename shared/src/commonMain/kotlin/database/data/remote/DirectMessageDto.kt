package database.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `DirectMessage`: one message waiting for the caller.
 *
 * Every message is between two contacts today, the acceptance of a request the caller
 * sent or a contact removing the caller, so [ciphertext] is a serialized
 * `message.DirectMessageEnvelope`. [senderUserUuid] is the server's word for who sent it,
 * MLS authenticates the actual sender once the message is read.
 */
@Serializable
data class DirectMessageDto(
    val uuid: String,

    @SerialName("sender_user_uuid")
    val senderUserUuid: String,

    val ciphertext: String,

    @SerialName("created_at")
    val createdAt: String,
)

/**
 * Body of `GET /messages`: a page of the caller's pending messages, oldest first, and
 * [nextCursor] to ask for the next page with, null when this is the last, see
 * [DirectMessageApi.getDirectMessagesPage]. Every page read, see
 * [DirectMessageApi.getDirectMessages], it holds them all, with no next cursor.
 */
@Serializable
data class ListDirectMessagesResponse(
    val messages: List<DirectMessageDto>,

    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

/** Body of `POST /messages`. */
@Serializable
data class SendDirectMessageBody(
    @SerialName("recipient_user_uuid")
    val recipientUserUuid: String,

    /** A serialized `message.DirectMessageEnvelope`, opaque to the server. */
    val ciphertext: String,

    /**
     * Which signal to wake the recipient with, and the one thing the server is told
     * about what is inside.
     *
     * Omitted by every caller that has nothing to gain from saying: the default says no
     * more than that something arrived.
     */
    val kind: DirectMessageKindDto = DirectMessageKindDto.MESSAGE,
)

/**
 * What a sender is willing to tell the server a direct message is about.
 *
 * Deliberately coarse, and closed on the server: it names the screen the recipient's
 * client would open, never who or what the message concerns.
 */
@Serializable
enum class DirectMessageKindDto {
    @SerialName("message")
    MESSAGE,

    /**
     * The message carries the key to a one-time expense.
     *
     * Said out loud because a locked device can read nothing but the signal, and told
     * only that a message arrived it has nothing safe to show: the same signal covers a
     * contact removing the user. The server learns nothing new, it already holds the
     * expense and its beneficiaries in the clear.
     */
    @SerialName("one_time_expense")
    ONE_TIME_EXPENSE,
}

/** Body of `POST /messages/acknowledgements`. */
@Serializable
data class AcknowledgeDirectMessagesBody(
    @SerialName("message_uuids")
    val messageUuids: List<String>,
)
