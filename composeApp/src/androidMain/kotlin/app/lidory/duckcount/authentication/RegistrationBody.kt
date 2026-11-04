package app.lidory.duckcount.authentication

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The kind of attestation [DeviceAttester] makes, the only one this app sends. */
const val ATTESTATION_PLATFORM = "android"

@Serializable
data class RegistrationChallengeResponse(
    @SerialName("attestation_challenge")
    val attestationChallenge: String,
)

@Serializable
data class RegistrationStartBody(
    val bytes: String,
    val username: String,

    // As the server handed it out: it looks the challenge up by this very string, and from that
    // lookup on it is spent whatever comes of this request.
    @SerialName("attestation_challenge")
    val attestationChallenge: String,

    // The phone's word for what it is, over that challenge. Mandatory: the server says nothing
    // about the username without one it accepts, see [DeviceAttester].
    val attestation: DeviceAttestationBody,
)

@Serializable
data class RegistrationStartResponse(
    // Names the registration when finishing it.
    @SerialName("session_id")
    val sessionId: String,

    // The server's half of the handshake, Base64 encoded.
    val bytes: String,
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

@Serializable
data class DeviceAttestationBody(
    // Which of the server's two kinds of attestation this is. No default, as the client's JSON
    // leaves out a property holding its default, and the server takes no body without it.
    val platform: String,

    // Leaf first, each certificate in standard Base64 DER, see [DeviceAttester.attest].
    @SerialName("certificate_chain")
    val certificateChain: List<String>,
)
