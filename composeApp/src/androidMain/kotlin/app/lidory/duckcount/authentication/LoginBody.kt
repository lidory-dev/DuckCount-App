package app.lidory.duckcount.authentication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginStartBody(
    @SerialName("credential_request_bytes")
    val credentialRequestBytes: String,

    val username: String,
)

@Serializable
data class LoginFinishBody(
    // The username is not repeated here: the server reads the user being logged in from
    // the state it stored when the login was started, keyed by this session id.
    @SerialName("session_id")
    val sessionId: String,

    @SerialName("credential_finalization_bytes")
    val credentialFinalizationBytes: String,
)
