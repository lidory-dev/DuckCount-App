package push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the server needs a client to know before it can subscribe. */
@Serializable
data class PushParametersDto(
    /**
     * The server's VAPID public key, an uncompressed P-256 point, base64url encoded
     * without padding.
     *
     * The distributor binds a subscription to it, and refuses a push signed with any
     * other, so this has to be in hand before subscribing rather than after.
     */
    @SerialName("vapid_public_key") val vapidPublicKey: String,
)

/** Where to wake this device. */
@Serializable
data class SavePushSubscriptionBody(
    /** The protocol the endpoint speaks, `web_push` on Android. */
    val transport: String,

    /** Where the server sends the signal. */
    val endpoint: String,

    /** The subscription's public key, base64url encoded without padding. */
    val p256dh: String?,

    /** The subscription's authentication secret, likewise. */
    val auth: String?,
)
