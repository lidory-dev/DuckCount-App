package database.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `ContactRequest`: one request addressed to the caller.
 *
 * [ciphertext] is a serialized `contact.ContactRequestEnvelope`: the Welcome into the
 * MLS group the sender created with one of the caller's key packages, and the sender's
 * profile encryption key encrypted to that group. Opening it is what lets the caller
 * read the sender's profile and decide whether to accept.
 */
@Serializable
data class ContactRequestDto(
    val uuid: String,

    @SerialName("sender_user_uuid")
    val senderUserUuid: String,

    val ciphertext: String,

    @SerialName("created_at")
    val createdAt: String,
)

/** Body of `GET /contacts/requests`: incoming requests only, oldest first. */
@Serializable
data class ListContactRequestsResponse(
    val requests: List<ContactRequestDto>,
)

/** Body of `GET /contacts/requests/{request_uuid}/acceptance`. */
@Serializable
data class ContactRequestStatusResponse(
    val status: ContactRequestStatusDto,
)

/**
 * What became of a request addressed to the caller.
 *
 * A status this build does not know fails to decode, and is taken for no answer at all.
 */
@Serializable
enum class ContactRequestStatusDto {
    /** Still waiting for the caller's answer. */
    @SerialName("pending")
    PENDING,

    /** Taken by the caller's acceptance. */
    @SerialName("accepted")
    ACCEPTED,

    /**
     * Gone otherwise: refused, cleared by its sender accepting the caller's own request,
     * with their account, or accepted by the caller and its record acknowledged since or
     * pruned, see [ContactApi.acknowledgeContactRequestAcceptance].
     */
    @SerialName("gone")
    GONE,
}

/** Body of `GET /contacts/lookup/{username}`. */
@Serializable
data class ContactLookupResponse(
    /** The user holding the looked-up username. */
    @SerialName("user_uuid")
    val userUuid: String,

    /**
     * One of their MLS key packages, Base64 encoded. The lookup consumed it, unless it is
     * their last-resort one.
     */
    @SerialName("key_package")
    val keyPackage: String,
)

/** Body of `POST /contacts/requests`. */
@Serializable
data class SendContactRequestBody(
    /**
     * The user the lookup resolved, rather than the username: the ciphertext is bound to
     * their key package, so it has to reach them even if the handle changes hands.
     */
    @SerialName("recipient_user_uuid")
    val recipientUserUuid: String,

    /** A serialized `ContactRequestEnvelope`, opaque to the server. */
    val ciphertext: String,
)

/** Body of `POST /contacts/requests/{request_uuid}/acceptance`. */
@Serializable
data class AcceptContactRequestBody(
    /**
     * A serialized `message.DirectMessageEnvelope`, opaque to the server, which hands
     * it to the request's sender as a direct message.
     */
    val ciphertext: String,
)
