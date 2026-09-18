package push

import authenticationUtils.clearRegisteredPushSubscription
import authenticationUtils.getRegisteredPushSubscription
import authenticationUtils.setRegisteredPushSubscription
import util.EmptyResult
import util.NetworkError
import util.Result

/** The transport an Android client's endpoint speaks, as the server names it. */
const val WEB_PUSH_TRANSPORT = "web_push"

/**
 * The subscription that lets the server wake this device.
 *
 * The server side of push, in other words: [PushRegistrar] is what obtains an
 * endpoint from the platform, and this is what the endpoint is then told to.
 *
 * A device is registered for as long as it is signed in. The registration is
 * replaced rather than added to, here and on the server both, since the account's
 * keys live on one device at a time anyway.
 */
class PushRepository(
    private val pushApi: PushApi,
) {
    /**
     * The server's VAPID public key, which a platform needs before it can hand out
     * an endpoint bound to it.
     */
    suspend fun vapidPublicKey(): Result<String, PushError> =
        when (val parameters = pushApi.getPushParameters()) {
            is Result.Success -> Result.Success(parameters.data.vapidPublicKey)
            is Result.Error -> Result.Error(PushError.Network(parameters.error))
        }

    /**
     * Tells the server where to wake this device.
     *
     * A subscription identical to the one this device last registered is not sent
     * again: a distributor hands back the same endpoint every time the app starts,
     * and re-registering it would spend the route's quota saying nothing.
     */
    suspend fun register(
        transport: String,
        endpoint: String,
        p256dh: String?,
        auth: String?,
    ): EmptyResult<PushError> {
        val subscription = identityOf(
            transport = transport,
            endpoint = endpoint,
            p256dh = p256dh,
            auth = auth,
        )

        if (subscription == getRegisteredPushSubscription()) return Result.Success(Unit)

        val save = pushApi.savePushSubscription(
            body = SavePushSubscriptionBody(
                transport = transport,
                endpoint = endpoint,
                p256dh = p256dh,
                auth = auth,
            ),
        )

        if (save is Result.Error) return Result.Error(PushError.Network(save.error))

        setRegisteredPushSubscription(subscription = subscription)

        return Result.Success(Unit)
    }

    /**
     * Tells the server to forget this device.
     *
     * The local copy is dropped whatever the server answers: a device that is on its
     * way out of a session must not go on believing it is registered, and a row left
     * behind is replaced by the next login anyway.
     */
    suspend fun unregister(): EmptyResult<PushError> {
        val delete = pushApi.deletePushSubscription()

        clearRegisteredPushSubscription()

        return when (delete) {
            is Result.Success -> Result.Success(Unit)

            is Result.Error -> when (delete.error) {
                // The session is already over, which is all unregistering was for.
                NetworkError.UNAUTHORIZED -> Result.Success(Unit)

                else -> Result.Error(PushError.Network(delete.error))
            }
        }
    }

    /**
     * What makes two registrations the same one.
     *
     * Compared, never parsed: the keys are part of it because a distributor may
     * rotate them while keeping the endpoint, and the server would then be encrypting
     * to keys this device no longer holds.
     */
    private fun identityOf(
        transport: String,
        endpoint: String,
        p256dh: String?,
        auth: String?,
    ): String = "$transport|$endpoint|$p256dh|$auth"
}
