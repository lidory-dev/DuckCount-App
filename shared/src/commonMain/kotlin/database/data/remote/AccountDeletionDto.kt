package database.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 */
@Serializable
data class FinishAccountDeletionRequestBody(
    @SerialName("credential_finalization_bytes")
    val credentialFinalizationBytes: String,
) {
    /**
     * The MLS commits taking the user out of the groups the server holds, one for each group
     * they would leave somebody behind in, which the server wants exactly.
     *
     * Always none: the app is a member of no group the server knows of. The MLS groups it builds
     * are the channels between two users, which the server never hears of, and the user's own
     * groups live on this device alone.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    @SerialName("group_commits")
    val groupCommits: List<Nothing> = emptyList()
}
