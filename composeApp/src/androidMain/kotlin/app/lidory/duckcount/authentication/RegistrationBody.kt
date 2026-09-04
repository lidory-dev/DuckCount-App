package app.lidory.duckcount.authentication

import database.data.remote.EncryptedFieldDto
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

    // The vault root key, sealed under the key derived from the password (the OPAQUE
    // export key) this handshake just established. Mandatory: the server writes it in
    // the same transaction as the account, because this is the only moment the client
    // holds the export key, and everything else the user will ever hold hangs off it.
    @SerialName("root_key")
    val rootKey: EncryptedFieldDto,
)
