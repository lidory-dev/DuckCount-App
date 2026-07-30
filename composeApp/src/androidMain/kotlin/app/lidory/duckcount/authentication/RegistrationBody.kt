package app.lidory.duckcount.authentication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegistrationStartBody(
    val bytes: String,
    val username: String,
)

@Serializable
data class RegistrationFinishBody(
    // The username is not repeated here: the server reads it from the state it stored
    // when the registration was started, keyed by this session id.
    @SerialName("session_id")
    val sessionId: String,

    @SerialName("message_bytes")
    val messageBytes: String,

    @SerialName("public_key")
    val publicKey: String,
)
