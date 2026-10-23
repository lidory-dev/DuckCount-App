package database.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /users/{user_uuid}/password`: the first messages of the two OPAQUE handshakes a
 * password change runs side by side, Base64 encoded, which the server answers with
 * [StartPasswordChangeResponse]. A login with the current password, which a session alone does
 * not stand in for, and a registration of the new one.
 */
@Serializable
data class StartPasswordChangeRequestBody(
    @SerialName("credential_request_bytes")
    val credentialRequestBytes: String,

    @SerialName("registration_request_bytes")
    val registrationRequestBytes: String,
)

/**
 * What `POST /users/{user_uuid}/password` answers: the server's half of both handshakes, Base64
 * encoded.
 */
@Serializable
data class StartPasswordChangeResponse(
    @SerialName("credential_response_bytes")
    val credentialResponseBytes: String,

    @SerialName("registration_response_bytes")
    val registrationResponseBytes: String,
)

/**
 * Body of `PUT /users/{user_uuid}/password`: the messages finishing both handshakes
 * [StartPasswordChangeRequestBody] started, Base64 encoded, and [rootKey], the vault's root key
 * sealed under the new password. The server stores the new password file and [rootKey] together,
 * once it has checked the login, and answers with a new pair of tokens, see
 * [networking.TokenResponse].
 */
@Serializable
data class FinishPasswordChangeRequestBody(
    @SerialName("credential_finalization_bytes")
    val credentialFinalizationBytes: String,

    @SerialName("registration_upload_bytes")
    val registrationUploadBytes: String,

    /**
     * The root key this device holds, the very one the old password sealed: everything else in
     * the vault hangs off it, and stays as it is, see `vault.VaultRepository`.
     */
    @SerialName("root_key")
    val rootKey: EncryptedFieldDto,
)
