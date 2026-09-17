package database.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `DirectMessage`: one message waiting for the caller.
 *
 * Every message is between two contacts today, the acceptance of a request the caller
 * sent or a contact removing the caller, so [ciphertext] is a serialized
 * `contact.ContactMessageEnvelope`. [senderUserUuid] is the server's word for who sent it,
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

/** Body of `GET /messages`: the caller's pending messages, oldest first. */
@Serializable
data class ListDirectMessagesResponse(
    val messages: List<DirectMessageDto>,
)

/** Body of `POST /messages`. */
@Serializable
data class SendDirectMessageBody(
    @SerialName("recipient_user_uuid")
    val recipientUserUuid: String,

    /** A serialized `contact.ContactMessageEnvelope`, opaque to the server. */
    val ciphertext: String,
)

/** Body of `POST /messages/acknowledgements`. */
@Serializable
data class AcknowledgeDirectMessagesBody(
    @SerialName("message_uuids")
    val messageUuids: List<String>,
)
