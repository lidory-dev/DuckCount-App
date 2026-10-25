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
     * The MLS commits taking the user out of the groups the server holds, at most one for each
     * group they leave somebody behind in.
     *
     * Always none, which the server takes: a commit there is the client's to give or not, since
     * an invitee who never joined a group has no MLS state to build one from, and the user leaves
     * every group all the same, with nothing relayed to its members. Nothing here builds one yet,
     * so the members' groups keep the user's leaf until removing a member is something the app
     * does.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    @SerialName("group_commits")
    val groupCommits: List<Nothing> = emptyList()
}
