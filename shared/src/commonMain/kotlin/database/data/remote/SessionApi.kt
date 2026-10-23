package database.data.remote

import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.TokenResponse
import util.NetworkError
import util.Result

/**
 * Remote data source for the login, as a device already signed in runs it: only to find out what
 * became of a password change whose answer never came back, see `account.PasswordChange`, with
 * the new password. Signing in runs its own, see `AuthenticationCallBacksImpl.login`.
 *
 * Sent through the `Auth` plugin, as any request is. Neither sets off a refresh, which would
 * present the long-lived token the server has just turned down: the server answers neither with
 * a `401`.
 */
class SessionApi(
    private val client: DuckCountClient,
) {
    /**
     * Starts an OPAQUE login. The server keeps the attempt, named by the session id it answers
     * with, for [finishLogin] to finish. An account it does not know is a `404`.
     */
    suspend fun startLogin(body: LoginStartBody): Result<LoginStartResponse, NetworkError> =
        client.post<LoginStartResponse, LoginStartBody>(
            route = DuckCountRoutes.Auth.SESSIONS,
            body = body,
        )

    /**
     * Finishes the login [startLogin] started, which retires the long-lived token this device
     * held, and answers with the new pair. The root key the answer carries too is not read: it is
     * the one this device holds already.
     */
    suspend fun finishLogin(body: LoginFinishBody): Result<TokenResponse, NetworkError> =
        client.put<TokenResponse, LoginFinishBody>(
            route = DuckCountRoutes.Auth.SESSIONS,
            body = body,
        )
}
