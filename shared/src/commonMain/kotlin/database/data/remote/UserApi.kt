package database.data.remote

import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.TokenResponse
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

    /**
     * Starts the two OPAQUE handshakes changing the caller's password takes: a login with the
     * current password, run with the session already open, since a session alone does not let
     * anybody change the password, and a registration of the new one.
     *
     * [userUuid] must be the authenticated user's, the server rejects anything else with a
     * `403`. It keeps the attempt for a minute, for [finishPasswordChange] to finish, and a new
     * one replaces it.
     */
    suspend fun startPasswordChange(
        userUuid: String,
        body: StartPasswordChangeRequestBody,
    ): Result<StartPasswordChangeResponse, NetworkError> =
        client.post<StartPasswordChangeResponse, StartPasswordChangeRequestBody>(
            route = DuckCountRoutes.User.PASSWORD,
            pathParams = mapOf("user_uuid" to userUuid),
            body = body,
        )

    /**
     * Finishes the handshakes [startPasswordChange] started and changes the caller's password,
     * storing the new password file and the root key sealed under it together, then answers with
     * a new pair of tokens. Committed, the change retires the long-lived token this device held at
     * once, with none of the grace a refresh leaves.
     *
     * The server uses the attempt up once it reads it, which it does only after checking the
     * path and the root key: a `403` for a [userUuid] that is not the caller's, or a `400` for a
     * root key missing its ciphertext or nonce, leaves it in place. Past that, a `404` is an
     * attempt that expired or was used up already, a `403` a message that does not finish this
     * attempt. A failure takes a new attempt either way: the library used up this device's half
     * of it in finishing it.
     */
    suspend fun finishPasswordChange(
        userUuid: String,
        body: FinishPasswordChangeRequestBody,
    ): Result<TokenResponse, NetworkError> =
        client.put<TokenResponse, FinishPasswordChangeRequestBody>(
            route = DuckCountRoutes.User.PASSWORD,
            pathParams = mapOf("user_uuid" to userUuid),
            body = body,
        )
}
