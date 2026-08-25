package database.data.remote

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/**
 * Remote data source for the authenticated user's account.
 */
class UserApi(
    private val client: DuckCountClient,
) {
    /**
     * Reserves the caller's discovery username, replacing any previous reservation.
     *
     * [userUuid] must be the authenticated user's, the server rejects anything else
     * with a `403`. A digest already held by somebody else comes back as a `409`.
     *
     * The route is rate-limited per user, and tightly: the `409` tells the caller a
     * digest is taken, so an unmetered route would let anybody enumerate the reserved
     * namespace. Both outcomes are counted, so reserving too often, successfully or
     * not, comes back as a `429`.
     */
    suspend fun reserveUsername(
        userUuid: String,
        body: ReserveUsernameRequestBody,
    ): Result<Unit, NetworkError> =
        client.put<Unit, ReserveUsernameRequestBody>(
            route = DuckCountRoutes.User.USERNAME,
            pathParams = mapOf("user_uuid" to userUuid),
            body = body,
        )
}
