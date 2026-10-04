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
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.clearAuthTokens
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hard cap on a call to our own backend.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

class DuckCountClient(
    private val sessionExpiry: SessionExpiry,
    private val localDataDao: LocalDataDao,
    private val sessionLeftovers: List<SessionLeftovers>,
) : Client() {
    /**
     * The pair a refresh could not store, which nothing else holds but the plugin. A process
     * that ended now would start the next one from the pair the server retired, so it is stored
     * before the next request goes out. Written under [tokenStorage] only, and read outside it
     * by the check most requests stop at, and by a refresh.
     */
    @Volatile
    private var unstoredTokens: TokenResponse? = null

    /**
     * Set by [endSession], after which no pair is stored again: a refresh that was in flight as
     * the session ended would otherwise put one back in the vault, and the next launch would
     * take it for a session. Only touched under [tokenStorage].
     */
    private var sessionEnded = false

    /** Keeps an older pair from being stored over a newer one, or over an ended session. */
    private val tokenStorage = Mutex()

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
                // Once the server has answered a refresh, the token this device held is gone:
                // a caller going away part-way must not take the only pair that works with it.
                nonCancellableRefresh = true

                loadTokens {
                    getShortLivedToken()?.let { shortLivedToken ->
                        BearerTokens(
                            accessToken = shortLivedToken,
                            refreshToken = getLongLivedToken(),
                        )
                    }
                }
                refreshTokens {
                    // The vault is a pair behind when the device locked before the last one could
                    // be stored, and the server no longer takes that one. Read before the vault:
                    // it is only set from in here, under the plugin's lock, and only cleared once
                    // the vault holds it, so one of the two reads has the newest.
                    //
                    // Not the pair the plugin holds, which can be older than the vault: one
                    // loaded before a login the same process went on to replace it with.
                    val unstoredToken = unstoredTokens?.longLivedToken

                    // Nothing to refresh with: this device is not (or no longer) logged in, so the
                    // `401` that got us here is final. Returning null stops the plugin from
                    // retrying and hands the caller the original response.
                    //
                    // Read even when the unstored one is what gets sent, because a locked device
                    // cannot read it, and throws: it could not store what the server sends back
                    // either, and the server would have retired the token all the same.
                    val storedToken = getLongLivedToken()?.takeIf { it.isNotBlank() }
                        ?: return@refreshTokens null

                    val refreshToken = unstoredToken ?: storedToken

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

                    // Returned whether or not it could be stored: it is the only pair the server
                    // takes now, and the plugin keeps what this returns.
                    BearerTokens(
                        accessToken = accessTokens.shortLivedToken,
                        refreshToken = accessTokens.longLivedToken,
                    )
                }
            }
        }
    }.also { client ->
        client.plugin(HttpSend).intercept { request ->
            storeUnstoredTokens()

            execute(request)
        }
    }

    /**
     * Stores [tokens], or keeps them for [storeUnstoredTokens] when the device is locked.
     *
     * Never throws for it: the refresh that got them has already retired the pair before them,
     * and one that threw would leave the plugin holding that one, see `refreshTokens`.
     */
    private suspend fun storeTokens(tokens: TokenResponse) = tokenStorage.withLock {
        if (sessionEnded) return@withLock

        unstoredTokens = tokens

        store(tokens = tokens)
    }

    /** Stores the pair a refresh could not, now that the device may be unlocked. */
    private suspend fun storeUnstoredTokens() {
        if (unstoredTokens == null) return

        tokenStorage.withLock {
            store(tokens = unstoredTokens ?: return@withLock)
        }
    }

    /** Only under [tokenStorage]. Leaves [unstoredTokens] set for as long as it fails. */
    private suspend fun store(tokens: TokenResponse) {
        try {
            setTokens(shortLivedToken = tokens.shortLivedToken, longLivedToken = tokens.longLivedToken)

            unstoredTokens = null
        } catch (exception: CancellationException) {
            // The caller going away, which the catch below would otherwise swallow.
            throw exception
        } catch (_: Exception) {
            // Kept for the next request: a locked device, most likely, see setTokens.
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
            // First, or a pair still to be stored would go back in the vault once it was cleared.
            tokenStorage.withLock {
                unstoredTokens = null
                sessionEnded = true
            }

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
