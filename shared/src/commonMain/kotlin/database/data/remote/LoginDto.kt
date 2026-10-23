package database.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /auth/sessions`: the first message of an OPAQUE login, Base64 encoded, and the
 * account it is for, which the server answers with [LoginStartResponse].
 */
@Serializable
data class LoginStartBody(
    @SerialName("credential_request_bytes")
    val credentialRequestBytes: String,

    /** The digest of the login username, see `app.lidory.duckcount.hashLoginUsername`. */
    val username: String,
)

/**
 * What `POST /auth/sessions` answers: the login it started, to name when finishing it, and the
 * server's half of it, Base64 encoded.
 */
@Serializable
data class LoginStartResponse(
    @SerialName("session_id")
    val sessionId: String,

    @SerialName("credential_response_bytes")
    val credentialResponseBytes: String,
)

/**
 * Body of `PUT /auth/sessions`: the message finishing the login [LoginStartBody] started, Base64
 * encoded, which the server answers with a new pair of tokens, see [networking.TokenResponse],
 * and the vault's root key.
 */
@Serializable
data class LoginFinishBody(
    // The username is not repeated here: the server reads the user being logged in from
    // the state it stored when the login was started, keyed by this session id.
    @SerialName("session_id")
    val sessionId: String,

    @SerialName("credential_finalization_bytes")
    val credentialFinalizationBytes: String,
)
