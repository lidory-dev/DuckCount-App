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
     * with a `403`. A digest already held by somebody else comes back as a `409`, and
     * one equal to the caller's own login username as a `400`.
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
