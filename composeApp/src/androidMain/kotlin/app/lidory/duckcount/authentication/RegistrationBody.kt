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
    @SerialName("message_bytes")
    val messageBytes: String,

    @SerialName("public_key")
    val publicKey: String,

    val username: String,

    )