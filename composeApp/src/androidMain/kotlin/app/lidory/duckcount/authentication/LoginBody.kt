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
    @SerialName("credential_finalization_bytes")
    val credentialFinalizationBytes: String,

    val username: String,
)
