package networking

import authenticationUtils.SessionExpiry
import authenticationUtils.clearSession
import authenticationUtils.getLongLivedToken
import authenticationUtils.getShortLivedToken
import authenticationUtils.setTokens
import database.clearDatabasePassword
import database.data.local.dao.LocalDataDao
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.clearAuthTokens
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Hard cap on a call to our own backend.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

class DuckCountClient(
    private val sessionExpiry: SessionExpiry,
    private val localDataDao: LocalDataDao,
) : Client() {
    override val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
        install(Logging) {
            logger = httpLogger
            level = LogLevel.ALL
        }
        install(ContentNegotiation) {
            json(
                json = Json {
                    ignoreUnknownKeys = true
                },
            )
        }
        install(Auth) {
            bearer {
                loadTokens {
                    getShortLivedToken()?.let { shortLivedToken ->
                        BearerTokens(
                            accessToken = shortLivedToken,
                            refreshToken = getLongLivedToken(),
                        )
                    }
                }
                refreshTokens {
                    // Nothing to refresh with: this device is not (or no longer) logged in, so the
                    // `401` that got us here is final. Returning null stops the plugin from
                    // retrying and hands the caller the original response.
                    val refreshToken = getLongLivedToken()?.takeIf { it.isNotBlank() }
                        ?: return@refreshTokens null

                    val response = client.post(
                        urlString = DuckCountRoutes.Auth.TOKEN,
                    ) {
                        setBody(hashMapOf("long_lived_token" to refreshToken))
                        contentType(ContentType.Application.Json)
                        markAsRefreshTokenRequest()
                    }

                    if (!response.status.isSuccess()) {
                        // `401` is the one thing this endpoint says about the long-lived token
                        // itself: expired, revoked or unknown. There is no way back to a valid
                        // session from here, so end it and let the UI take the user to the login
                        // screen.
                        //
                        // Its only other failure is `500`, which is transient and says nothing
                        // about the session, so it costs the caller this one request and no more.
                        if (response.status == HttpStatusCode.Unauthorized) expireSession()

                        return@refreshTokens null
                    }

                    val accessTokens = response.body<TokenResponse>()

                    setTokens(
                        accessTokens.shortLivedToken,
                        accessTokens.longLivedToken,
                    )

                    BearerTokens(
                        accessToken = accessTokens.shortLivedToken,
                        refreshToken = accessTokens.longLivedToken,
                    )
                }
            }
        }
    }

    /**
     * Catches the `401`s the refresh above could not act on: the ones where this device holds no
     * credentials at all, yet something is still calling an authenticated endpoint.
     *
     * Reachable without the session having been revoked: the tokens are sealed against an unlocked
     * device, so a Keystore key invalidated by a lock-screen change reads back as no token while
     * navigation still sits on the dashboard.
     *
     * A `401` with the credentials still in place is left alone. It means the refresh either
     * succeeded (and the server turned the request down for its own reasons) or failed for a
     * transient reason, and neither is worth throwing the user out over.
     */
    override suspend fun onUnauthorized() {
        if (getLongLivedToken().isNullOrBlank()) expireSession()
    }

    /**
     * Ends the session on this device: the credentials in the vault, the bearer tokens the [Auth]
     * plugin caches in memory, and everything the database holds for the account.
     *
     * The cached half matters as much as the stored one. Tokens are read once through `loadTokens`
     * and then kept until a `401` forces a refresh, so without this the requests that follow a log-out
     * would still carry the previous session's token.
     *
     * The database goes too: it was filled for this account alone, and whoever signs in next on
     * this device must not find it there. Emptying it clears the rows; dropping the key it is
     * sealed with clears what SQLite left behind in the pages those rows used to occupy, which no
     * DELETE reaches. The next launch finds no key, discards the file it can no longer open and
     * starts a database of its own.
     *
     * Not cancellable once started. A forced log-out runs inside whichever request got the `401`,
     * whose caller can go away at any point, and stopping after the credentials but before the
     * database would leave the account's data behind on a device that no longer knows whose it is.
     */
    suspend fun endSession() {
        withContext(NonCancellable) {
            clearSession()

            httpClient.clearAuthTokens()

            localDataDao.deleteAll()

            clearDatabasePassword()
        }
    }

    /**
     * [endSession], plus the signal that makes the UI react. Used when the server ended the
     * session for us, as opposed to the user asking for it.
     */
    private suspend fun expireSession() {
        endSession()

        sessionExpiry.signal()
    }
}
