package networking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("long_lived_token")
    val longLivedToken: String,

    @SerialName("short_lived_token")
    val shortLivedToken: String,
)