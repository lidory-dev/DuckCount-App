package networking

import authenticationUtils.SessionExpiry
import authenticationUtils.SessionLeftovers
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Hard cap on a call to our own backend.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

/** Where every authentication route lives, see [DuckCountRoutes.Auth]. */
private const val AUTH_PATH = "/auth/"

class DuckCountClient(
    private val sessionExpiry: SessionExpiry,
    private val localDataDao: LocalDataDao,
    private val sessionLeftovers: List<SessionLeftovers>,
) : Client() {
    /**
     * Set by [endSession], after which no pair is stored again: a refresh that was in flight as
     * the session ended would otherwise put one back in the vault, and the next launch would
     * take it for a session. Only touched under [tokenStorage].
     */
    private var sessionEnded = false

    /** Keeps a pair from being stored over an ended session, see [sessionEnded]. */
    private val tokenStorage = Mutex()

    override val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
        if (logsHttpTraffic) {
            install(Logging) {
                logger = httpLogger
                level = LogLevel.ALL

                // Even in a debug build, nothing that would let whoever reads the log act as
                // the user: the bearer token on every request, and the calls to the
                // authentication routes, whose bodies carry the long-lived token one way or the
                // other. Their failures still show, logged by whatever made the call. A route
                // added later that returns tokens belongs with them, as the password change will.
                sanitizeHeader { it.equals(HttpHeaders.Authorization, ignoreCase = true) }
                filter { !it.url.encodedPath.startsWith(AUTH_PATH) }
            }
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
                    //
                    // The vault's, never the pair the plugin holds. The server takes the token a
                    // refresh replaced until the new one is first used, so the one the vault kept
                    // goes on working when the device could not store its replacement, see
                    // [storeTokens]. Sending that replacement instead would retire it, and leave
                    // the vault, and whatever process comes next, a token the server turns down.
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

                    storeTokens(tokens = accessTokens)

                    // Whether or not it could be stored: its short-lived token is good for the
                    // next 20 minutes, where the plugin's has just expired.
                    BearerTokens(
                        accessToken = accessTokens.shortLivedToken,
                        refreshToken = accessTokens.longLivedToken,
                    )
                }
            }
        }
    }

    /**
     * Stores [tokens], unless the session has ended.
     *
     * Never throws. A pair the vault cannot take, on a device that locked during the refresh
     * most likely, is no loss: the server still takes the token the vault holds, which is what
     * the next refresh sends.
     */
    private suspend fun storeTokens(tokens: TokenResponse) = tokenStorage.withLock {
        if (sessionEnded) return@withLock

        // Seen through even if the caller goes away. Until the write has either landed or been
        // rolled back, the vault hands the new token to whoever reads it, and a refresh waiting
        // on this one would send it: that retires the token the vault falls back to when the
        // write fails.
        withContext(NonCancellable) {
            try {
                setTokens(shortLivedToken = tokens.shortLivedToken, longLivedToken = tokens.longLivedToken)
            } catch (_: Exception) {
                // Left to the next refresh, see above.
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
     * plugin caches in memory, everything the database holds for the account, and whatever the
     * platform keeps of it elsewhere, see [SessionLeftovers].
     *
     * Here rather than in whatever restarts the app, because a session does not always end in
     * front of the user: a worker whose refresh is turned down ends it with no screen up.
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
     * That next launch has to come before the next session, so the caller restarts the process
     * once this returns (`restartApp` on Android). This process opened the database with the key
     * just dropped and would go on writing under it, and a launch finding no key discards all of
     * that too, including MLS secrets nothing can bring back. A future iOS log-out needs the same.
     *
     * Not cancellable once started. A forced log-out runs inside whichever request got the `401`,
     * whose caller can go away at any point, and stopping after the credentials but before the
     * database would leave the account's data behind on a device that no longer knows whose it is.
     */
    suspend fun endSession() {
        withContext(NonCancellable) {
            // First, or a refresh still in flight could store its pair after the vault was cleared.
            tokenStorage.withLock { sessionEnded = true }

            clearSession()

            httpClient.clearAuthTokens()

            localDataDao.deleteAll()

            clearDatabasePassword()

            sessionLeftovers.forEach { it.clear() }
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
