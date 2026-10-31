package database.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /users/{user_uuid}/deletion`: the first message of an OPAQUE login, Base64
 * encoded, which the server answers with [StartAccountDeletionResponse].
 */
@Serializable
data class StartAccountDeletionRequestBody(
    @SerialName("credential_request_bytes")
    val credentialRequestBytes: String,
)

/** What `POST /users/{user_uuid}/deletion` answers: the server's half of the login, Base64 encoded. */
@Serializable
data class StartAccountDeletionResponse(
    @SerialName("credential_response_bytes")
    val credentialResponseBytes: String,
)

/**
 * Body of `PUT /users/{user_uuid}/deletion`: the message finishing the login
 * [StartAccountDeletionRequestBody] started, Base64 encoded, which deletes the account once the
 * server has checked it.
 *
 * Nothing of the user's groups: the server lists the user as departed from each, and wakes their
 * members, whose devices take the user's leaf out of its MLS group, see `group.GroupSync`.
 */
@Serializable
data class FinishAccountDeletionRequestBody(
    @SerialName("credential_finalization_bytes")
    val credentialFinalizationBytes: String,
)
