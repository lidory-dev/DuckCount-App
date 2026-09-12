package database.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `ContactRequest`: one request addressed to the caller.
 *
 * [ciphertext] is the sender's profile encryption key, sealed to one of the caller's
 * MLS key packages. Unsealing it is what lets the caller read the sender's profile
 * and decide whether to accept, so until that exists the app can count requests but
 * not say who sent them.
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
