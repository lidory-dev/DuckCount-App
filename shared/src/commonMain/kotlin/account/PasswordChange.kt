package account

import authenticationUtils.getRootKey
import authenticationUtils.setHasPasswordBeenChecked
import database.data.remote.EncryptedFieldDto
import database.data.remote.FinishPasswordChangeRequestBody
import database.data.remote.LoginFinishBody
import database.data.remote.LoginStartBody
import database.data.remote.LoginStartResponse
import database.data.remote.SessionApi
import database.data.remote.StartPasswordChangeRequestBody
import database.data.remote.StartPasswordChangeResponse
import database.data.remote.UserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import networking.DuckCountClient
import networking.HaveIBeenPwnedClient
import networking.TokenResponse
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.Login
import uniffi.lib_duckcount.LoginInterface
import uniffi.lib_duckcount.PasswordChangeStartResult
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64
import app.lidory.duckcount.hashLoginUsername as hashLoginUsernameShared
import uniffi.lib_duckcount.PasswordChange as LibraryPasswordChange

/**
 * Changes the user's password, once they have typed the current one, and nothing else: the
 * account keeps its identity and its keys, and this device its session, its data and its MLS
 * state.
 *
 * Three things change, together on the server: the OPAQUE password file, the seal on the vault's
 * root key and the session's tokens. The root key itself stays the one the old password sealed,
 * see `vault.VaultRepository`: the key the new password derives seals it, and it seals the key
 * ring, which seals everything else, none of which is sealed again.
 *
 * In this order:
 * 1. The new password is looked up in the known breaches, see
 *    [HaveIBeenPwnedClient.passwordLeakCount]. One found there stops everything here, before our
 *    server is told anything. A check that could not be run lets the change go ahead without it,
 *    as registration does, and whether it ran is recorded once the change is made, as
 *    registration records it, see [setHasPasswordBeenChecked].
 * 2. The two handshakes changing it takes are run, see [handshake]: a login with the current
 *    password, which the server asks for since a session alone does not let anybody change the
 *    password, and a registration of the new one. A wrong current password stops everything
 *    here, before anything is sent that would change it.
 * 3. The session is refreshed, see [DuckCountClient.refreshSession], then the change goes out,
 *    and the pair of tokens it answers with replaces the session's, see [send].
 *
 * The server retires the long-lived token this device holds the moment it commits the change,
 * with none of the grace an exchange leaves the token it replaced: a refresh presenting it after
 * would be turned down, and its `401` end the session, taking the database and the MLS state with
 * it. The refresh made just before the change keeps any request sent after it from setting one
 * off: each carries a short-lived token good for the next 20 minutes, and a request refreshes
 * only once its token has expired. One sent before it still carries the token before, and still
 * can: should that token expire while the change is out, well under a second, the 5 s timeout at
 * most, with such a request in flight then, its refresh may reach the server after the commit.
 * That race is accepted, for how rarely the two meet.
 *
 * The server changes the password before it answers, so a change whose answer never comes back
 * may have gone through: what became of it is found out straight away, with the new password
 * still in hand, see [settle].
 *
 * [rootKey] is [getRootKey], [leakCount] is [HaveIBeenPwnedClient.passwordLeakCount],
 * [noteChecked] is [setHasPasswordBeenChecked], [newHandshakes] runs the library's
 * [uniffi.lib_duckcount.PasswordChange], [startChange] and [finishChange] are
 * [UserApi.startPasswordChange] and [UserApi.finishPasswordChange], [refreshSession],
 * [replaceTokens] and [isSessionAccepted] are [DuckCountClient.refreshSession],
 * [DuckCountClient.replaceTokens] and [DuckCountClient.isSessionAccepted], [newLogin] makes the
 * library's [Login], [hashUsername] is `app.lidory.duckcount.hashLoginUsername`, [startLogin] and
 * [finishLogin] are [SessionApi.startLogin] and [SessionApi.finishLogin], or what a test stands
 * in for any of them.
 */
class PasswordChange internal constructor(
    private val rootKey: suspend () -> ByteArray?,
    private val leakCount: suspend (password: String) -> Result<Int, NetworkError>,
    private val noteChecked: (isChecked: Boolean) -> Unit,
    private val newHandshakes: () -> PasswordHandshakes,
    private val startChange: suspend (
        userUuid: String,
        body: StartPasswordChangeRequestBody,
    ) -> Result<StartPasswordChangeResponse, NetworkError>,
    private val refreshSession: suspend () -> EmptyResult<NetworkError>,
    private val finishChange: suspend (
        userUuid: String,
        body: FinishPasswordChangeRequestBody,
    ) -> Result<TokenResponse, NetworkError>,
    private val replaceTokens: suspend (tokens: TokenResponse) -> Unit,
    private val isSessionAccepted: suspend () -> Result<Boolean, NetworkError>,
    private val newLogin: () -> LoginInterface,
    private val hashUsername: (username: String) -> String,
    private val startLogin: suspend (body: LoginStartBody) -> Result<LoginStartResponse, NetworkError>,
    private val finishLogin: suspend (body: LoginFinishBody) -> Result<TokenResponse, NetworkError>,
) {
    constructor(
        userApi: UserApi,
        sessionApi: SessionApi,
        duckCountClient: DuckCountClient,
        haveIBeenPwnedClient: HaveIBeenPwnedClient,
    ) : this(
        rootKey = ::getRootKey,
        leakCount = haveIBeenPwnedClient::passwordLeakCount,
        noteChecked = ::setHasPasswordBeenChecked,
        newHandshakes = { LibraryHandshakes() },
        startChange = userApi::startPasswordChange,
        refreshSession = duckCountClient::refreshSession,
        finishChange = userApi::finishPasswordChange,
        replaceTokens = duckCountClient::replaceTokens,
        isSessionAccepted = duckCountClient::isSessionAccepted,
        newLogin = { Login() },
        hashUsername = ::hashLoginUsernameShared,
        startLogin = sessionApi::startLogin,
        finishLogin = sessionApi::finishLogin,
    )

    /**
     * Changes the password of [userUuid], the user signed in as [username], from
     * [currentPassword] to [newPassword].
     *
     * Not cancellable once the change is about to be sent: the server may have made it by then,
     * and the session on this device has to follow, or end at the next refresh. The screen
     * asking can be left meanwhile. Nor does it return, the change made, before the vault holds
     * the pair it answered with, which a device locked meanwhile holds up until it is unlocked,
     * see [DuckCountClient.replaceTokens].
     */
    suspend fun change(
        userUuid: String,
        username: String,
        currentPassword: String,
        newPassword: String,
    ): EmptyResult<PasswordChangeError> {
        val rootKey = rootKey() ?: return Result.Error(PasswordChangeError.MissingRootKey)

        val isChecked = when (val leaks = leakCount(newPassword)) {
            is Result.Success -> if (leaks.data > 0) return Result.Error(PasswordChangeError.Leaked) else true

            // Advisory, as at registration: a check that could not be run lets the change through.
            is Result.Error -> false
        }

        val finish = when (
            val handshakes = handshake(
                userUuid = userUuid,
                currentPassword = currentPassword,
                newPassword = newPassword,
                rootKey = rootKey,
            )
        ) {
            is Result.Success -> handshakes.data
            is Result.Error -> return handshakes
        }

        return withContext(NonCancellable) {
            val change = send(userUuid = userUuid, username = username, newPassword = newPassword, body = finish)

            if (change is Result.Success) noteChecked(isChecked)

            change
        }
    }

    /**
     * Runs the two handshakes changing the password of [userUuid] takes up to the message
     * finishing them, which carries [rootKey] sealed under [newPassword], see
     * [PasswordHandshakes.finish], for [send] to send.
     *
     * The library finishes the login with [currentPassword] here, on this device, so a wrong one
     * is found out before anything is sent that would change the password.
     */
    private suspend fun handshake(
        userUuid: String,
        currentPassword: String,
        newPassword: String,
        rootKey: ByteArray,
    ): Result<FinishPasswordChangeRequestBody, PasswordChangeError> =
        try {
            val handshakes = newHandshakes()

            // Off the caller's thread, which may be the main one: finishing derives a key from
            // each password with Argon2.
            val started = withContext(Dispatchers.Default) {
                handshakes.start(currentPassword = currentPassword, newPassword = newPassword)
            }

            val answered = startChange(
                userUuid,
                StartPasswordChangeRequestBody(
                    credentialRequestBytes = Base64.Default.encode(started.credentialRequest),
                    registrationRequestBytes = Base64.Default.encode(started.registrationRequest),
                ),
            )

            when (answered) {
                is Result.Success -> Result.Success(
                    withContext(Dispatchers.Default) {
                        handshakes.finish(
                            credentialResponseBytes = Base64.Default.decode(answered.data.credentialResponseBytes),
                            registrationResponseBytes = Base64.Default.decode(answered.data.registrationResponseBytes),
                            currentPassword = currentPassword,
                            newPassword = newPassword,
                            rootKey = rootKey,
                        )
                    },
                )

                is Result.Error -> Result.Error(PasswordChangeError.Network(answered.error))
            }
        } catch (_: AuthenticationException.ClientLoginFinalizationFailed) {
            // How OPAQUE turns down a wrong password, as it does when signing in.
            Result.Error(PasswordChangeError.WrongPassword)
        } catch (_: AuthenticationException) {
            Result.Error(PasswordChangeError.Crypto)
        } catch (_: IllegalArgumentException) {
            // The server's half that is not Base64.
            Result.Error(PasswordChangeError.Crypto)
        }

    /**
     * Sends the change [body] finishes, once the session is refreshed, see [refreshSession], and
     * stores the pair of tokens it answers with as the session's, see [replaceTokens]: the
     * long-lived token the vault holds is no longer taken once the change is made.
     *
     * That refresh turned down, the session has ended, as for any refresh turned down, and nothing
     * is sent. Nor when it could not be made, the change failing for why: a vault this device
     * cannot read, or could not store the refresh's pair in, on a device locked since the user
     * asked, is [NetworkError.UNAUTHORIZED], see [DuckCountClient.refreshSession].
     *
     * A change whose answer never came back is [settle]d.
     */
    private suspend fun send(
        userUuid: String,
        username: String,
        newPassword: String,
        body: FinishPasswordChangeRequestBody,
    ): EmptyResult<PasswordChangeError> {
        val refreshed = refreshSession()

        if (refreshed is Result.Error) return Result.Error(PasswordChangeError.Network(refreshed.error))

        return when (val change = finishChange(userUuid, body)) {
            is Result.Success -> {
                replaceTokens(change.data)

                Result.Success(Unit)
            }

            is Result.Error ->
                if (change.error.leavesChangeOpen()) {
                    settle(username = username, newPassword = newPassword, error = change.error)
                } else {
                    Result.Error(PasswordChangeError.Network(change.error))
                }
        }
    }

    /**
     * Finds out whether a change that failed for [error], leaving that open, went through after
     * all, from whether the server still takes the session, see [isSessionAccepted]: the change
     * retires its long-lived token.
     *
     * Taken, nothing changed, and [error] is reported. Turned down, the change went through, or
     * the session ended for another reason, a login on another device or 90 days without a
     * refresh, which a login with [newPassword] tells apart, see [logIn]. Not asked,
     * [PasswordChangeError.Unconfirmed].
     *
     * A change the server is still running when asked, held up past the timeout, can find the
     * session still taken and go through all the same, retiring the token that exchange stored:
     * the session then ends at the next refresh, as an expired one, the user logging in again
     * with the new password.
     */
    private suspend fun settle(
        username: String,
        newPassword: String,
        error: NetworkError,
    ): EmptyResult<PasswordChangeError> =
        when (val asked = isSessionAccepted()) {
            is Result.Success ->
                if (asked.data) {
                    Result.Error(PasswordChangeError.Network(error))
                } else {
                    logIn(username = username, newPassword = newPassword, error = error)
                }

            is Result.Error -> Result.Error(PasswordChangeError.Unconfirmed(error))
        }

    /**
     * Logs [username] in with [newPassword], with the session turned down after a change that
     * failed for [error]: the change went through if it logs in.
     *
     * For the tokens alone, which stand for the pair the change's lost answer carried, see
     * [replaceTokens]. None of what else signing in does: this device still holds the account's
     * session, its keys and its data, and the root key the answer carries is the one it holds.
     *
     * Turned down by OPAQUE on this device, [newPassword] is not the account's, so the change
     * was not made, and the session the server turned down ended for another reason: it ends
     * here, see [ended]. Anything else keeping the login from being run leaves what became of
     * the change unknown, [PasswordChangeError.Unconfirmed].
     */
    private suspend fun logIn(
        username: String,
        newPassword: String,
        error: NetworkError,
    ): EmptyResult<PasswordChangeError> =
        try {
            val login = newLogin()

            val request = withContext(Dispatchers.Default) { login.start(newPassword) }

            val started = startLogin(
                LoginStartBody(
                    credentialRequestBytes = Base64.Default.encode(request),
                    username = hashUsername(username),
                ),
            )

            when (started) {
                is Result.Success -> {
                    val finalization = withContext(Dispatchers.Default) {
                        login.finish(
                            credentialResponseBytes = Base64.Default.decode(started.data.credentialResponseBytes),
                            password = newPassword,
                        ).message
                    }

                    val finished = finishLogin(
                        LoginFinishBody(
                            sessionId = started.data.sessionId,
                            credentialFinalizationBytes = Base64.Default.encode(finalization),
                        ),
                    )

                    when (finished) {
                        is Result.Success -> {
                            replaceTokens(finished.data)

                            Result.Success(Unit)
                        }

                        is Result.Error -> Result.Error(PasswordChangeError.Unconfirmed(error))
                    }
                }

                is Result.Error -> Result.Error(PasswordChangeError.Unconfirmed(error))
            }
        } catch (_: AuthenticationException.ClientLoginFinalizationFailed) {
            ended()
        } catch (_: AuthenticationException) {
            Result.Error(PasswordChangeError.Unconfirmed(error))
        } catch (_: IllegalArgumentException) {
            // The server's half that is not Base64.
            Result.Error(PasswordChangeError.Unconfirmed(error))
        }

    /**
     * Ends the session the server turned down as a refresh turned down ends it, which restarts
     * the app telling the user, by refreshing it once more, see [refreshSession]: the server turns
     * it down again. Reports the `401` a request whose refresh was turned down reports. That
     * refresh unanswered, the session ends at the next one instead.
     */
    private suspend fun ended(): EmptyResult<PasswordChangeError> {
        refreshSession()

        return Result.Error(PasswordChangeError.Network(NetworkError.UNAUTHORIZED))
    }
}

/**
 * The two handshakes of a password change, as [PasswordChange] runs them: the library's
 * [uniffi.lib_duckcount.PasswordChange] in its own shapes, see [LibraryHandshakes], but for the
 * object the library finishes them with, which only the library can make, so that a test can
 * stand in for them.
 */
internal interface PasswordHandshakes {

    /** Starts both handshakes, see [uniffi.lib_duckcount.PasswordChange.start]. */
    fun start(currentPassword: String, newPassword: String): PasswordChangeStartResult

    /**
     * Finishes both handshakes, see [uniffi.lib_duckcount.PasswordChange.finish], into the body
     * sending the change, [rootKey] sealed under [newPassword] in it. The login comes first, so a
     * wrong [currentPassword] throws [AuthenticationException.ClientLoginFinalizationFailed]
     * before anything is sealed.
     */
    fun finish(
        credentialResponseBytes: ByteArray,
        registrationResponseBytes: ByteArray,
        currentPassword: String,
        newPassword: String,
        rootKey: ByteArray,
    ): FinishPasswordChangeRequestBody
}

/** The [PasswordHandshakes] the library runs. */
private class LibraryHandshakes : PasswordHandshakes {

    private val passwordChange = LibraryPasswordChange()

    override fun start(currentPassword: String, newPassword: String): PasswordChangeStartResult =
        passwordChange.start(currentPassword = currentPassword, newPassword = newPassword)

    override fun finish(
        credentialResponseBytes: ByteArray,
        registrationResponseBytes: ByteArray,
        currentPassword: String,
        newPassword: String,
        rootKey: ByteArray,
    ): FinishPasswordChangeRequestBody {
        val finished = passwordChange.finish(
            credentialResponseBytes = credentialResponseBytes,
            registrationResponseBytes = registrationResponseBytes,
            currentPassword = currentPassword,
            newPassword = newPassword,
            rootKey = rootKey,
        )

        val sealedRootKey = finished.encryptedRootKey()

        return FinishPasswordChangeRequestBody(
            credentialFinalizationBytes = Base64.Default.encode(finished.credentialFinalization()),
            registrationUploadBytes = Base64.Default.encode(finished.registrationUpload()),
            rootKey = EncryptedFieldDto(
                ciphertext = Base64.Default.encode(sealedRootKey.ciphertext()),
                nonce = Base64.Default.encode(sealedRootKey.nonce()),
            ),
        )
    }
}

/**
 * Whether a change that failed this way may have gone through all the same. The server changes
 * the password and only then answers, so an answer that timed out or a connection dropped before
 * it came leave it open, and so does a `5xx`, which the proxy in front of the API may return
 * after the commit. Every other failure is a refusal the server answers before changing anything,
 * or one that kept the change from being sent.
 */
private fun NetworkError.leavesChangeOpen(): Boolean =
    when (this) {
        NetworkError.REQUEST_TIMEOUT,
        NetworkError.UNREACHABLE,
        NetworkError.SERVER_ERROR,
            -> true

        NetworkError.BAD_REQUEST,
        NetworkError.UNAUTHORIZED,
        NetworkError.NOT_FOUND,
        NetworkError.CONFLICT,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        NetworkError.TOO_MANY_REQUESTS,
        NetworkError.NO_INTERNET,
        NetworkError.SERIALIZATION,
        NetworkError.UNKNOWN,
            -> false
    }
