package app.lidory.duckcount.authentication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenBody(
    @SerialName("long_lived_token")
    val longLivedToken: String,
)
