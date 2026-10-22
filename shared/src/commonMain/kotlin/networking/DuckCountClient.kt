package networking

import authenticationUtils.SessionEnd
import authenticationUtils.SessionExpiry
import authenticationUtils.SessionLeftovers
import authenticationUtils.clearAccountDeletionInDoubt
import authenticationUtils.clearSession
import authenticationUtils.getLongLivedToken
import authenticationUtils.getShortLivedToken
import authenticationUtils.isAccountDeletionInDoubt
import authenticationUtils.setTokens
import database.clearDatabasePassword
import database.data.local.dao.LocalDataDao
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthCircuitBreaker
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
import io.ktor.http.HttpMethod
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
import util.NetworkError
import util.Result

/**
 * Hard cap on a call to our own backend.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

/** Where every authentication route lives, see [DuckCountRoutes.Auth]. */
private const val AUTH_PATH = "/auth/"

/** The path of [DuckCountRoutes.User.DELETION], whoever's account it names. */
private val DELETION_PATH = Regex("/users/[^/]+/deletion")

/**
 * Whether a request with [method] to [encodedPath] is the one deleting the account, the `PUT` of
 * [DuckCountRoutes.User.DELETION], see `account.AccountDeletion`. Not the `POST` that starts the
 * re-authentication before it: that one deletes nothing.
 */
internal fun isAccountDeletion(method: HttpMethod, encodedPath: String): Boolean =
    method == HttpMethod.Put && DELETION_PATH.matches(encodedPath)

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

    /**
     * Set while a deletion of the account is out, see [whileDeletionOut], during which no
     * exchange of the long-lived token rules it out, see [storeTokens]. Only touched under
     * [tokenStorage].
     */
    private var isDeletionOut = false

    /**
     * Held for every exchange of the long-lived token, the [Auth] plugin's refresh and
     * [isSessionAccepted] alike. The server takes the token an exchange replaced only until the
     * new one is first used, and an exchange presenting it again retires that new one: two
     * exchanges reading the vault's token at once could leave it a token the server no longer
     * takes. The plugin keeps its own refreshes from overlapping, but not one of these.
     */
    private val tokenExchange = Mutex()

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
                // other. Nor the account deletion's, an OPAQUE login on the user's password like
                // the one signing in runs, which is no more the log's business. Their failures
                // still show, logged by whatever made the call. A route added later that returns
                // tokens belongs with them, as the password change will.
                sanitizeHeader { it.equals(HttpHeaders.Authorization, ignoreCase = true) }
                filter { request ->
                    val path = request.url.encodedPath

                    !path.startsWith(AUTH_PATH) && !DELETION_PATH.matches(path)
                }
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
                    // Read off the `401` that set the refresh off, which the exchange's answer
                    // below shadows. The deletion's own is the server turning it down at the
                    // short-lived token, before it touched the account, see [expireSession].
                    val deletionTurnedDown = isAccountDeletion(
                        method = response.call.request.method,
                        encodedPath = response.call.request.url.encodedPath,
                    )

                    tokenExchange.withLock {
                        // Nothing to refresh with: this device is not (or no longer) logged in, so
                        // the `401` that got us here is final. Returning null stops the plugin from
                        // retrying and hands the caller the original response.
                        //
                        // The vault's, never the pair the plugin holds. The server takes the token
                        // a refresh replaced until the new one is first used, so the one the vault
                        // kept goes on working when the device could not store its replacement, see
                        // [storeTokens]. Sending that replacement instead would retire it, and leave
                        // the vault, and whatever process comes next, a token the server turns down.
                        val refreshToken = getLongLivedToken()?.takeIf { it.isNotBlank() }
                            ?: return@withLock null

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
                            // session from here, so end it and let the UI take the user to the
                            // login screen.
                            //
                            // Its only other failure is `500`, which is transient and says nothing
                            // about the session, so it costs the caller this one request and no more.
                            if (response.status == HttpStatusCode.Unauthorized) {
                                expireSession(accountStays = deletionTurnedDown)
                            }

                            return@withLock null
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
    }

    /**
     * Stores [tokens], the server's answer to an exchange of the long-lived token, unless the
     * session has ended.
     *
     * Never throws. A pair the vault cannot take, on a device that locked during the refresh
     * most likely, is no loss: the server still takes the token the vault holds, which is what
     * the next refresh sends.
     */
    private suspend fun storeTokens(tokens: TokenResponse) = tokenStorage.withLock {
        if (sessionEnded) return@withLock

        // The token exchanged would have gone with the account, so a deletion this device sent
        // and never had the answer to did not go through, see [clearAccountDeletionInDoubt].
        // Unless it is still out: the server can exchange the token and then delete the account,
        // see [whileDeletionOut].
        if (!isDeletionOut) clearAccountDeletionInDoubt()

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
     * Runs [deletion], which records a deletion of the account in doubt and sends it, see
     * `account.AccountDeletion`, with no exchange of the long-lived token made meanwhile taken to
     * rule it out, see [storeTokens]. The deletion reaches the server whenever it does, after an
     * exchange as readily as before one: one another request's refresh made while it was on its
     * way, or the one its own `401` sets off, after which the [Auth] plugin sends it again with
     * the new token.
     */
    suspend fun <T> whileDeletionOut(deletion: suspend () -> T): T {
        tokenStorage.withLock { isDeletionOut = true }

        try {
            return deletion()
        } finally {
            withContext(NonCancellable) {
                tokenStorage.withLock { isDeletionOut = false }
            }
        }
    }

    /**
     * Asks the server whether it still takes this device's session, by exchanging the long-lived
     * token the vault holds as a refresh does, and stores the pair it answers with as a refresh
     * stores it, see [storeTokens]: true while it does, false once it turns the token down, the
     * `401` a refresh ends the session on, see [expireSession], or why it could not be asked.
     *
     * Nothing is ended or signalled here, unlike a refresh: what a session turned down means is
     * the caller's to say. That token goes with the account, see `account.AccountDeletion`, but
     * also with a login on another device, or 90 days without a refresh.
     *
     * Sent past the [Auth] plugin, so it neither carries the short-lived token nor sets off a
     * refresh. The vault holding no token, or none this device can read, is a session that
     * could not be asked about, [NetworkError.UNAUTHORIZED].
     */
    suspend fun isSessionAccepted(): Result<Boolean, NetworkError> = tokenExchange.withLock {
        val refreshToken = try {
            getLongLivedToken()?.takeIf { it.isNotBlank() }
        } catch (_: IllegalStateException) {
            // The vault is sealed against a locked device, which this one may have become since
            // the caller started: a deletion carries on with the screen off, see
            // `account.AccountDeletion`.
            null
        } ?: return@withLock Result.Error(NetworkError.UNAUTHORIZED)

        val exchange = execute<TokenResponse> {
            httpClient.post(urlString = DuckCountRoutes.Auth.TOKEN) {
                setBody(hashMapOf("long_lived_token" to refreshToken))
                contentType(ContentType.Application.Json)
                attributes.put(AuthCircuitBreaker, Unit)
            }
        }

        when (exchange) {
            is Result.Success -> {
                storeTokens(tokens = exchange.data)

                Result.Success(true)
            }

            is Result.Error ->
                if (exchange.error == NetworkError.UNAUTHORIZED) Result.Success(false) else exchange
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
     * transient reason, and neither is worth throwing the user out over. Or that no refresh was
     * tried: [isSessionAccepted] exchanges the token past the [Auth] plugin, and its `401` is the
     * server turning the session down, which is the caller's to act on.
     *
     * Nor is one this device cannot read the credentials for, on a device locked since the request
     * went out, a deletion's last check on the session among them, see `account.AccountDeletion`:
     * they are still there, out of reach until it is unlocked.
     */
    override suspend fun onUnauthorized() {
        val longLivedToken = try {
            getLongLivedToken()
        } catch (_: IllegalStateException) {
            return
        }

        if (longLivedToken.isNullOrBlank()) expireSession()
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
     *
     * Not for a session this device ended already. A log-out or an account deletion ends it and
     * restarts the app, and whatever request was still running then, a sync a push started for
     * instance, goes out with no credentials and comes back `401`: signalled, the restart that
     * follows could be the one telling the user their session expired.
     *
     * Nor the session's end when the account went with a deletion this device sent and never had
     * the answer to: the user is told so instead, see [SessionEnd.ACCOUNT_DELETED]. Read before
     * the session's end drops the record of it.
     *
     * Unless [accountStays], the refresh having been set off by the `401` the deletion itself got:
     * the server turned it down at the short-lived token, before it touched the account, which
     * the re-authentication it was sent after had just found there, see `account.AccountDeletion`.
     * A token turned down then went with something else, a login on another device most likely,
     * and the session expired. One turned down for any other request while a deletion is out goes
     * by the record: the deletion may have reached the server by then, the likelier reason of the
     * two.
     */
    private suspend fun expireSession(accountStays: Boolean = false) {
        if (tokenStorage.withLock { sessionEnded }) return

        val end = if (!accountStays && isAccountDeletionInDoubt()) SessionEnd.ACCOUNT_DELETED else SessionEnd.EXPIRED

        endSession()

        sessionExpiry.signal(end = end)
    }
}
