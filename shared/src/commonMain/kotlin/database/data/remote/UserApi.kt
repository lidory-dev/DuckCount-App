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
    suspend fun reserveDiscoveryUsername(
        userUuid: String,
        body: ReserveDiscoveryUsernameRequestBody,
    ): Result<Unit, NetworkError> =
        client.put<Unit, ReserveDiscoveryUsernameRequestBody>(
            route = DuckCountRoutes.User.USERNAME,
            pathParams = mapOf("user_uuid" to userUuid),
            body = body,
        )

    /**
     * Starts the re-authentication deleting the caller's account takes: an OPAQUE login, run
     * with the session already open, since a session alone does not let anybody delete the
     * account.
     *
     * [userUuid] must be the authenticated user's, the server rejects anything else with a
     * `403`. It keeps the attempt for a minute, for [finishAccountDeletion] to finish, and a
     * new one replaces it.
     */
    suspend fun startAccountDeletion(
        userUuid: String,
        body: StartAccountDeletionRequestBody,
    ): Result<StartAccountDeletionResponse, NetworkError> =
        client.post<StartAccountDeletionResponse, StartAccountDeletionRequestBody>(
            route = DuckCountRoutes.User.DELETION,
            pathParams = mapOf("user_uuid" to userUuid),
            body = body,
        )

    /**
     * Finishes the re-authentication [startAccountDeletion] started and deletes the caller's
     * account: everything the server holds of it but the expenses, which the other people on
     * them still need. Nobody is told.
     *
     * The attempt is used up whatever comes of it, so a failure takes a new one. A `404` is an
     * attempt that expired or was used up already, a `403` a message that does not finish this
     * attempt.
     */
    suspend fun finishAccountDeletion(
        userUuid: String,
        body: FinishAccountDeletionRequestBody,
    ): Result<Unit, NetworkError> =
        client.put<Unit, FinishAccountDeletionRequestBody>(
            route = DuckCountRoutes.User.DELETION,
            pathParams = mapOf("user_uuid" to userUuid),
            body = body,
        )
}
