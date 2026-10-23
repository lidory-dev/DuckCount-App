package account

import database.data.remote.EncryptedFieldDto
import database.data.remote.FinishPasswordChangeRequestBody
import database.data.remote.LoginFinishBody
import database.data.remote.LoginStartBody
import database.data.remote.LoginStartResponse
import database.data.remote.StartPasswordChangeRequestBody
import database.data.remote.StartPasswordChangeResponse
import kotlinx.coroutines.runBlocking
import networking.TokenResponse
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.EncryptedField
import uniffi.lib_duckcount.LoginFinishResult
import uniffi.lib_duckcount.LoginInterface
import uniffi.lib_duckcount.PasswordChangeStartResult
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val USER = "22222222-2222-2222-2222-222222222222"
private const val USERNAME = "duck"

private const val PASSWORD = "correct horse battery staple"
private const val NEW_PASSWORD = "tremble glacier marmalade oboe"

private val ROOT_KEY = byteArrayOf(4, 8, 15, 16, 23, 42)

/** What the change answers with. */
private val CHANGED = TokenResponse(longLivedToken = "changed long", shortLivedToken = "changed short")

/** What a login with the new password answers with. */
private val LOGGED_IN = TokenResponse(longLivedToken = "logged in long", shortLivedToken = "logged in short")

/**
 * The user changing their password, with the library's handshakes, the breach check, the server
 * and the session stood in for.
 */
class PasswordChangeTest {

    /** What reached the breach check, the handshakes, the server and the session, in order. */
    private val events = mutableListOf<String>()

    /** The account's password on the server, which a change that goes through replaces. */
    private var accountPassword = PASSWORD

    private var storedRootKey: ByteArray? = ROOT_KEY

    private var leakOutcome: Result<Int, NetworkError> = Result.Success(0)

    private var startOutcome: Result<Unit, NetworkError> = Result.Success(Unit)

    /**
     * What the server answers each exchange of the long-lived token with, in order: whether it
     * takes the token, or why it could not be asked. Taken once these run out.
     */
    private val exchangeOutcomes = ArrayDeque<Result<Boolean, NetworkError>>()

    /** What the server answers the change with. */
    private var changeOutcome: Result<TokenResponse, NetworkError> = Result.Success(CHANGED)

    /** Whether the change goes through on the server, which its answer being lost leaves open. */
    private var changeGoesThrough = true

    private var loginStartOutcome: Result<Unit, NetworkError> = Result.Success(Unit)

    /** The bodies each change carried. */
    private val changes = mutableListOf<FinishPasswordChangeRequestBody>()

    /** The logins started, by the username they named. */
    private val logins = mutableListOf<String>()

    private val passwordChange = PasswordChange(
        rootKey = { storedRootKey },
        leakCount = { password ->
            assertEquals(NEW_PASSWORD, password)
            events += "breach check"
            leakOutcome
        },
        noteChecked = { isChecked -> events += "checked $isChecked" },
        newHandshakes = { HandshakesStandIn() },
        startChange = ::start,
        refreshSession = ::refreshSession,
        finishChange = ::finish,
        replaceTokens = { tokens -> events += "store ${tokens.longLivedToken}" },
        isSessionAccepted = ::isSessionAccepted,
        newLogin = { LoginStandIn() },
        hashUsername = { username -> "digest of $username" },
        startLogin = ::startLogin,
        finishLogin = ::finishLogin,
    )

    @Test
    fun `a change sends the root key sealed under the new password and stores the pair it answers with`() =
        runBlocking<Unit> {
            assertIs<Result.Success<Unit>>(change())

            assertEquals(
                listOf(
                    "breach check",
                    "start",
                    "handshakes finished",
                    "refresh",
                    "finish",
                    "store ${CHANGED.longLivedToken}",
                    "checked true",
                ),
                events,
            )
            assertEquals(sealed(rootKey = ROOT_KEY, password = NEW_PASSWORD), changes.single().rootKey)
            assertEquals(NEW_PASSWORD, accountPassword)
        }

    /**
     * The handshakes take their time, Argon2 twice: the refresh comes after them, so that the
     * short-lived token every request carries while the change is out is a fresh one.
     */
    @Test
    fun `the session is refreshed once the handshakes are run and just before the change is sent`() =
        runBlocking<Unit> {
            change()

            val refresh = events.indexOf("refresh")

            assertTrue(refresh > events.indexOf("handshakes finished"))
            assertEquals("finish", events[refresh + 1])
        }

    /** OPAQUE turns the current password down on this device, before anything would change it. */
    @Test
    fun `a wrong current password stops before the change is sent`() = runBlocking<Unit> {
        val outcome = change(currentPassword = "not it")

        assertEquals(PasswordChangeError.WrongPassword, assertIs<Result.Error<PasswordChangeError>>(outcome).error)
        assertEquals(listOf("breach check", "start"), events)
        assertEquals(PASSWORD, accountPassword)
    }

    @Test
    fun `a missing root key stops before anything is sent`() = runBlocking<Unit> {
        storedRootKey = null

        val outcome = change()

        assertEquals(PasswordChangeError.MissingRootKey, assertIs<Result.Error<PasswordChangeError>>(outcome).error)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `a leaked new password stops before our server is told anything`() = runBlocking<Unit> {
        leakOutcome = Result.Success(3)

        val outcome = change()

        assertEquals(PasswordChangeError.Leaked, assertIs<Result.Error<PasswordChangeError>>(outcome).error)
        assertEquals(listOf("breach check"), events)
    }

    /** As at registration: the check only advises. */
    @Test
    fun `a breach check that could not be run lets the change through and is recorded as not run`() =
        runBlocking<Unit> {
            leakOutcome = Result.Error(NetworkError.REQUEST_TIMEOUT)

            assertIs<Result.Success<Unit>>(change())

            assertEquals(listOf("store ${CHANGED.longLivedToken}", "checked false"), events.takeLast(2))
        }

    @Test
    fun `a change the server will not start sends nothing more`() = runBlocking<Unit> {
        startOutcome = Result.Error(NetworkError.NO_INTERNET)

        val outcome = change()

        assertEquals(
            PasswordChangeError.Network(NetworkError.NO_INTERNET),
            assertIs<Result.Error<PasswordChangeError>>(outcome).error,
        )
        assertEquals(listOf("breach check", "start"), events)
    }

    /** As a refresh turned down ends it. */
    @Test
    fun `a session turned down before the change is sent ends and nothing is sent`() = runBlocking<Unit> {
        exchangeOutcomes += Result.Success(false)

        val outcome = change()

        assertEquals(
            PasswordChangeError.Network(NetworkError.UNAUTHORIZED),
            assertIs<Result.Error<PasswordChangeError>>(outcome).error,
        )
        assertEquals(listOf("refresh", "expire session"), events.takeLast(2))
        assertEquals(emptyList(), changes)
    }

    @Test
    fun `a session that could not be refreshed before the change is sent sends nothing`() = runBlocking<Unit> {
        exchangeOutcomes += Result.Error(NetworkError.UNREACHABLE)

        val outcome = change()

        assertEquals(
            PasswordChangeError.Network(NetworkError.UNREACHABLE),
            assertIs<Result.Error<PasswordChangeError>>(outcome).error,
        )
        assertEquals("refresh", events.last())
        assertEquals(emptyList(), changes)
        assertEquals(PASSWORD, accountPassword)
    }

    /**
     * The attempt expired, or the root key was turned down: refused before anything changed, so
     * there is nothing to find out about.
     */
    @Test
    fun `a change the server refuses is reported without asking about the session`() = runBlocking<Unit> {
        listOf(NetworkError.NOT_FOUND, NetworkError.BAD_REQUEST, NetworkError.UNKNOWN).forEach { refusal ->
            events.clear()
            changeOutcome = Result.Error(refusal)
            changeGoesThrough = false

            val outcome = change()

            assertEquals(PasswordChangeError.Network(refusal), assertIs<Result.Error<PasswordChangeError>>(outcome).error)
            assertEquals(listOf("refresh", "finish"), events.takeLast(2))
        }
    }

    /** The change would have retired the token the server still takes: nothing changed. */
    @Test
    fun `a change whose answer never came with the session still taken is reported`() = runBlocking<Unit> {
        changeOutcome = Result.Error(NetworkError.REQUEST_TIMEOUT)
        changeGoesThrough = false

        val outcome = change()

        assertEquals(
            PasswordChangeError.Network(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<PasswordChangeError>>(outcome).error,
        )
        assertEquals(listOf("refresh", "finish", "session check"), events.takeLast(3))
        assertEquals(emptyList(), logins)
    }

    /**
     * The server changed the password, then its answer was lost, to the timeout, to a dropped
     * connection or to the proxy: the session is turned down, and a login with the new password
     * stands for the pair the answer carried.
     */
    @Test
    fun `a change whose answer never came that went through logs in with the new password`() = runBlocking<Unit> {
        listOf(NetworkError.REQUEST_TIMEOUT, NetworkError.UNREACHABLE, NetworkError.SERVER_ERROR).forEach { lost ->
            events.clear()
            logins.clear()
            accountPassword = PASSWORD
            changeOutcome = Result.Error(lost)
            exchangeOutcomes += Result.Success(true)
            exchangeOutcomes += Result.Success(false)

            assertIs<Result.Success<Unit>>(change())

            assertEquals(
                listOf(
                    "finish",
                    "session check",
                    "login start",
                    "login finish",
                    "store ${LOGGED_IN.longLivedToken}",
                    "checked true",
                ),
                events.takeLast(6),
            )
            assertEquals(listOf("digest of $USERNAME"), logins)
        }
    }

    /**
     * The session was turned down for another reason, a login on another device for instance:
     * the new password is not the account's, and the session ends as an expired one.
     */
    @Test
    fun `a change whose answer never came whose new password does not log in ends the session`() =
        runBlocking<Unit> {
            changeOutcome = Result.Error(NetworkError.UNREACHABLE)
            changeGoesThrough = false
            exchangeOutcomes += Result.Success(true)
            exchangeOutcomes += Result.Success(false)
            exchangeOutcomes += Result.Success(false)

            val outcome = change()

            assertEquals(
                PasswordChangeError.Network(NetworkError.UNAUTHORIZED),
                assertIs<Result.Error<PasswordChangeError>>(outcome).error,
            )
            assertEquals(listOf("session check", "login start", "refresh", "expire session"), events.takeLast(4))
            assertFalse("login finish" in events)
        }

    @Test
    fun `a change whose answer never came that could not be asked about is unconfirmed`() = runBlocking<Unit> {
        changeOutcome = Result.Error(NetworkError.REQUEST_TIMEOUT)
        exchangeOutcomes += Result.Success(true)
        exchangeOutcomes += Result.Error(NetworkError.NO_INTERNET)

        val outcome = change()

        assertEquals(
            PasswordChangeError.Unconfirmed(NetworkError.REQUEST_TIMEOUT),
            assertIs<Result.Error<PasswordChangeError>>(outcome).error,
        )
        assertEquals(listOf("finish", "session check"), events.takeLast(2))
        assertFalse("expire session" in events)
    }

    @Test
    fun `a change whose answer never came whose login could not be run is unconfirmed`() = runBlocking<Unit> {
        changeOutcome = Result.Error(NetworkError.SERVER_ERROR)
        exchangeOutcomes += Result.Success(true)
        exchangeOutcomes += Result.Success(false)
        loginStartOutcome = Result.Error(NetworkError.REQUEST_TIMEOUT)

        val outcome = change()

        assertEquals(
            PasswordChangeError.Unconfirmed(NetworkError.SERVER_ERROR),
            assertIs<Result.Error<PasswordChangeError>>(outcome).error,
        )
        assertEquals(listOf("session check", "login start"), events.takeLast(2))
        assertFalse("expire session" in events)
    }

    /** Whatever answered is not the server's half of the handshakes. */
    @Test
    fun `a server half that is not Base64 is refused before the change is sent`() = runBlocking<Unit> {
        val misanswered = PasswordChange(
            rootKey = { ROOT_KEY },
            leakCount = { Result.Success(0) },
            noteChecked = { error("Not used here") },
            newHandshakes = { HandshakesStandIn() },
            startChange = { _, _ ->
                Result.Success(
                    StartPasswordChangeResponse(credentialResponseBytes = "not base64!", registrationResponseBytes = "AQ=="),
                )
            },
            refreshSession = { error("Not used here") },
            finishChange = { _, _ -> error("Not used here") },
            replaceTokens = { error("Not used here") },
            isSessionAccepted = { error("Not used here") },
            newLogin = { error("Not used here") },
            hashUsername = { error("Not used here") },
            startLogin = { error("Not used here") },
            finishLogin = { error("Not used here") },
        )

        val outcome = misanswered.change(
            userUuid = USER,
            username = USERNAME,
            currentPassword = PASSWORD,
            newPassword = NEW_PASSWORD,
        )

        assertEquals(PasswordChangeError.Crypto, assertIs<Result.Error<PasswordChangeError>>(outcome).error)
    }

    private suspend fun change(currentPassword: String = PASSWORD): EmptyResult<PasswordChangeError> =
        passwordChange.change(
            userUuid = USER,
            username = USERNAME,
            currentPassword = currentPassword,
            newPassword = NEW_PASSWORD,
        )

    private suspend fun start(
        userUuid: String,
        body: StartPasswordChangeRequestBody,
    ): Result<StartPasswordChangeResponse, NetworkError> {
        assertEquals(USER, userUuid)

        events += "start"

        return when (val outcome = startOutcome) {
            // The server's halves answer the requests they were sent.
            is Result.Success -> Result.Success(
                StartPasswordChangeResponse(
                    credentialResponseBytes = body.credentialRequestBytes,
                    registrationResponseBytes = body.registrationRequestBytes,
                ),
            )

            is Result.Error -> outcome
        }
    }

    /**
     * As `DuckCountClient.refreshSession` does with what the server answers its exchange with: a
     * token turned down ends the session.
     */
    private suspend fun refreshSession(): EmptyResult<NetworkError> {
        events += "refresh"

        return when (val exchange = exchange()) {
            is Result.Success ->
                if (exchange.data) {
                    Result.Success(Unit)
                } else {
                    events += "expire session"

                    Result.Error(NetworkError.UNAUTHORIZED)
                }

            is Result.Error -> Result.Error(exchange.error)
        }
    }

    /** As `DuckCountClient.isSessionAccepted` does, ending nothing. */
    private suspend fun isSessionAccepted(): Result<Boolean, NetworkError> {
        events += "session check"

        return exchange()
    }

    private fun exchange(): Result<Boolean, NetworkError> = exchangeOutcomes.removeFirstOrNull() ?: Result.Success(true)

    private suspend fun finish(
        userUuid: String,
        body: FinishPasswordChangeRequestBody,
    ): Result<TokenResponse, NetworkError> {
        assertEquals(USER, userUuid)

        events += "finish"
        changes += body

        if (changeGoesThrough) accountPassword = NEW_PASSWORD

        return changeOutcome
    }

    private suspend fun startLogin(body: LoginStartBody): Result<LoginStartResponse, NetworkError> {
        events += "login start"
        logins += body.username

        return when (val outcome = loginStartOutcome) {
            is Result.Success -> Result.Success(
                LoginStartResponse(sessionId = "login", credentialResponseBytes = body.credentialRequestBytes),
            )

            is Result.Error -> outcome
        }
    }

    private suspend fun finishLogin(body: LoginFinishBody): Result<TokenResponse, NetworkError> {
        assertEquals("login", body.sessionId)

        events += "login finish"

        return Result.Success(LOGGED_IN)
    }

    /**
     * Stands in for the library's handshakes: they finish only with the account's current
     * password, as OPAQUE turns any other down, and seal the root key under the new one.
     */
    private inner class HandshakesStandIn : PasswordHandshakes {

        override fun start(currentPassword: String, newPassword: String): PasswordChangeStartResult =
            PasswordChangeStartResult(credentialRequest = byteArrayOf(1), registrationRequest = byteArrayOf(2))

        override fun finish(
            credentialResponseBytes: ByteArray,
            registrationResponseBytes: ByteArray,
            currentPassword: String,
            newPassword: String,
            rootKey: ByteArray,
        ): FinishPasswordChangeRequestBody {
            if (currentPassword != accountPassword) {
                throw AuthenticationException.ClientLoginFinalizationFailed("wrong password")
            }

            events += "handshakes finished"

            return FinishPasswordChangeRequestBody(
                credentialFinalizationBytes = Base64.Default.encode(credentialResponseBytes),
                registrationUploadBytes = Base64.Default.encode(registrationResponseBytes),
                rootKey = sealed(rootKey = rootKey, password = newPassword),
            )
        }
    }

    /** Stands in for the library's login: it finishes only with the account's password. */
    private inner class LoginStandIn : LoginInterface {

        override fun start(password: String): ByteArray = byteArrayOf(3)

        override fun finish(credentialResponseBytes: ByteArray, password: String): LoginFinishResult {
            if (password != accountPassword) {
                throw AuthenticationException.ClientLoginFinalizationFailed("wrong password")
            }

            return LoginFinishResult(message = credentialResponseBytes)
        }

        override fun unsealRootKey(encryptedRootKey: EncryptedField): ByteArray = error("Not used here")
    }
}

/** [rootKey] as the stand-in for the library seals it under [password]. */
private fun sealed(rootKey: ByteArray, password: String): EncryptedFieldDto =
    EncryptedFieldDto(ciphertext = "${Base64.Default.encode(rootKey)} under $password", nonce = "nonce")
