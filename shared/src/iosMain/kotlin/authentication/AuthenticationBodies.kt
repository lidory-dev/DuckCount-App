package authentication

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/** The kind of attestation [AppAttester] makes, the only one this app sends from an iPhone. */
internal const val ATTESTATION_PLATFORM = "ios"

@Serializable
internal data class RegistrationChallengeResponse(
    @SerialName("attestation_challenge")
    val attestationChallenge: String,
)

@Serializable
internal data class RegistrationStartBody(
    val bytes: String,
    val username: String,

    // As the server handed it out: it looks the challenge up by this very string, and from that
    // lookup on it is spent whatever comes of this request.
    @SerialName("attestation_challenge")
    val attestationChallenge: String,

    // The device's word for what it is, over that challenge, see [AppAttester].
    val attestation: AppAttestationBody,
)

@Serializable
internal data class AppAttestationBody(
    // Which of the server's two kinds of attestation this is. No default, as the client's JSON
    // leaves out a property holding its default, and the server takes no body without it.
    val platform: String,

    // Standard Base64 CBOR, see [Attestation.Attested].
    @SerialName("attestation_object")
    val attestationObject: String,
)

@Serializable
internal data class RegistrationStartResponse(
    // Names the registration when finishing it.
    @SerialName("session_id")
    val sessionId: String,

    // The server's half of the handshake, Base64 encoded.
    val bytes: String,
)

@Serializable
internal data class RegistrationFinishBody(
    // The username is not repeated here: the server reads it from the state it stored when the
    // registration was started, keyed by this session id.
    @SerialName("session_id")
    val sessionId: String,

    @SerialName("message_bytes")
    val messageBytes: String,

    // The vault root key, sealed under the key derived from the password (the OPAQUE export key)
    // this handshake just established, which the server writes with the account.
    @SerialName("root_key")
    val rootKey: EncryptedFieldDto,
)

/**
 * What `PUT /auth/sessions` answers a login with: a new pair of tokens, and the vault's root key,
 * sealed under the key the password derives, which `database.data.remote.SessionApi.finishLogin`
 * has no use for, re-authenticating a device that holds it already.
 */
@Serializable
internal data class LoginFinishResponse(
    @SerialName("long_lived_token")
    val longLivedToken: String,

    @SerialName("short_lived_token")
    val shortLivedToken: String,

    @SerialName("root_key")
    val rootKey: EncryptedFieldDto? = null,
)

// JWTs use base64url, conventionally without padding; accept both padded and unpadded.
private val jwtBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * The `sub` claim of this JWT, which the server carries the user's UUID in, or null if the token is
 * malformed or the claim is absent or blank.
 *
 * Only decodes the payload, without verifying the signature: the token comes straight from our
 * own server's answer, over TLS.
 */
internal fun String.jwtSubject(): String? {
    val segments = split(".")
    if (segments.size < 2) return null

    return runCatching {
        val payload = jwtBase64.decode(segments[1]).decodeToString()

        Json.parseToJsonElement(payload).jsonObject["sub"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf(String::isNotBlank)
}
