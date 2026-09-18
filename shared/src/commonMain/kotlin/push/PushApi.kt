package push

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/**
 * Remote data source for the subscription that lets the server wake this device.
 *
 * Everything here is about *where* to reach the device, never about what to tell it:
 * what the server sends is a signal with no content, and what the user ends up
 * seeing is decided on the device once it has read the message the signal was about.
 */
class PushApi(
    private val client: DuckCountClient,
) {
    /** The parameters a client needs before it subscribes. */
    suspend fun getPushParameters(): Result<PushParametersDto, NetworkError> =
        client.get<PushParametersDto>(route = DuckCountRoutes.Push.PARAMETERS)

    /** Registers where to wake this device, replacing any earlier registration. */
    suspend fun savePushSubscription(
        body: SavePushSubscriptionBody,
    ): Result<Unit, NetworkError> =
        client.put<Unit, SavePushSubscriptionBody>(
            route = DuckCountRoutes.Push.SUBSCRIPTION,
            body = body,
        )

    /** Forgets where to wake this device. */
    suspend fun deletePushSubscription(): Result<Unit, NetworkError> =
        client.delete<Unit>(route = DuckCountRoutes.Push.SUBSCRIPTION)
}
