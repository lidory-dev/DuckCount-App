package app.lidory.duckcount.authentication

import AuthError
import AuthOutcome
import database.data.remote.EncryptedFieldDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import networking.Client
import networking.TokenResponse
import uniffi.lib_duckcount.AuthenticationException
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val USERNAME = "alice"
private const val PASSWORD = "correct horse battery staple"

/** As the server hands one out: standard Base64 of 32 bytes, with `+`, `/` and the padding. */
private const val CHALLENGE = "4OHi4+Tl5ufo6err7O3u7/Dx8vP09fb3+Pn6+/z9/v8="

private val CHALLENGE_BYTES = ByteArray(32) { (0xE0 + it).toByte() }

private val CERTIFICATE_CHAIN = listOf("leaf", "batch", "intermediate", "root")

/** The handshake's first message, the server's half of it and its last message, each in Base64 too. */
private val REQUEST = byteArrayOf(1, 2, 3)
private const val REQUEST_BASE64 = "AQID"
private val RESPONSE = byteArrayOf(4, 5, 6)
private const val RESPONSE_BASE64 = "BAUG"
private val MESSAGE = byteArrayOf(7, 8, 9)
private const val MESSAGE_BASE64 = "BwgJ"

private const val SESSION_ID = "session"

private val ROOT_KEY = byteArrayOf(10, 11, 12)

private val TOKENS = TokenResponse(longLivedToken = "long", shortLivedToken = "short")

private const val URL = "https://api.duckcount.test/auth/registrations"

/** Answers that do not read as the one asked for. */
private val MALFORMED_ANSWERS = listOf("not json", "", """{"session_id":""", """{"unexpected":1}""")

/**
 * The user creating their account, up to what is left to store on this device, with the library's
 * handshake, the phone's keystore and the server stood in for.
 */
class AccountRegistrationTest {

    /** What reached the server, the keystore and the handshake, in order. */
    private val events = mutableListOf<String>()

    /** What the keystore was asked to attest. */
    private val attested = mutableListOf<ByteArray>()

    private val startBodies = mutableListOf<RegistrationStartBody>()

    private val finishBodies = mutableListOf<RegistrationFinishBody>()

    /** The server's half the handshake finished with. */
    private val finishedWith = mutableListOf<ByteArray>()

    private var finishingThread: Thread? = null

    private var challengeAnswer: Result<RegistrationChallengeResponse, NetworkError> =
        Result.Success(RegistrationChallengeResponse(attestationChallenge = CHALLENGE))

    private var attestation: List<String>? = CERTIFICATE_CHAIN

    private var startAnswer: Result<RegistrationStartResponse, NetworkError> =
        Result.Success(RegistrationStartResponse(sessionId = SESSION_ID, bytes = RESPONSE_BASE64))

    private var finishAnswer: Result<TokenResponse, NetworkError> = Result.Success(TOKENS)

    private var startFailure: AuthenticationException? = null

    private var finishFailure: AuthenticationException? = null

    private var isOnline = true

    @Test
    fun `the phone attests between the challenge and the start`() = runBlocking<Unit> {
        register()

        assertEquals(
            listOf("challenge", "attest", "handshake started", "start", "handshake finished", "finish"),
            events,
        )
    }

    /** The chain has to hold what the server keeps under the string, which is not the string. */
    @Test
    fun `the phone attests over the bytes of the challenge`() = runBlocking<Unit> {
        register()

        assertContentEquals(CHALLENGE_BYTES, attested.single())
    }

    /** The server looks the challenge up by this very string, so anything else is a `404`. */
    @Test
    fun `the start sends the challenge back as it came`() = runBlocking<Unit> {
        register()

        assertEquals(CHALLENGE, startBodies.single().attestationChallenge)
    }

    @Test
    fun `the start carries the attestation`() = runBlocking<Unit> {
        register()

        assertEquals(
            DeviceAttestationBody(platform = "android", certificateChain = CERTIFICATE_CHAIN),
            startBodies.single().attestation,
        )
    }

    /** The server only ever knows the digest of the username, never the username itself. */
    @Test
    fun `the start names the digest of the username and starts the handshake`() = runBlocking<Unit> {
        register()

        assertEquals("digest of $USERNAME", startBodies.single().username)
        assertEquals(REQUEST_BASE64, startBodies.single().bytes)
    }

    @Test
    fun `the handshake finishes with the half of the server`() = runBlocking<Unit> {
        register()

        assertContentEquals(RESPONSE, finishedWith.single())
    }

    /** Argon2 runs there, and the caller may be on the main thread. */
    @Test
    fun `the handshake finishes off the thread of the caller`() = runBlocking<Unit> {
        register()

        assertNotEquals(Thread.currentThread(), finishingThread)
    }

    @Test
    fun `the finish names the session and carries no attestation`() = runBlocking<Unit> {
        register()

        assertEquals(
            """
            {"session_id":"$SESSION_ID","message_bytes":"$MESSAGE_BASE64",
            "root_key":{"ciphertext":"sealed","nonce":"nonce"}}
            """.trimIndent().replace("\n", ""),
            Json.encodeToString(RegistrationFinishBody.serializer(), finishBodies.single()),
        )
    }

    @Test
    fun `a registration leaves the session and the root key to store`() = runBlocking<Unit> {
        val account = assertIs<AuthOutcome.Success<RegisteredAccount>>(register()).data

        assertEquals(TOKENS, account.tokens)
        assertContentEquals(ROOT_KEY, account.rootKey)
    }

    /** The server hears of the username only with an attestation to go with it. */
    @Test
    fun `no attestation stops everything before the start`() = runBlocking<Unit> {
        attestation = null

        assertEquals(AuthOutcome.Failure(AuthError.DEVICE_CHECK_FAILED), register())
        assertEquals(listOf("challenge", "attest"), events)
    }

    @Test
    fun `a failed challenge attests nothing`() = runBlocking<Unit> {
        challengeAnswer = Result.Error(NetworkError.SERVER_ERROR)

        assertEquals(AuthOutcome.Failure(AuthError.SERVER_ERROR), register())
        assertEquals(listOf("challenge"), events)
    }

    @Test
    fun `a 403 to the challenge is a phone the server refused`() = runBlocking<Unit> {
        challengeAnswer = Result.Error(NetworkError.FORBIDDEN)

        assertEquals(AuthOutcome.Failure(AuthError.DEVICE_NOT_ALLOWED), register())
    }

    @Test
    fun `a 403 to the start is a phone the server refused and finishes nothing`() = runBlocking<Unit> {
        startAnswer = Result.Error(NetworkError.FORBIDDEN)

        assertEquals(AuthOutcome.Failure(AuthError.DEVICE_NOT_ALLOWED), register())
        assertEquals(listOf("challenge", "attest", "handshake started", "start"), events)
    }

    @Test
    fun `a 403 to the finish is a phone the server refused`() = runBlocking<Unit> {
        finishAnswer = Result.Error(NetworkError.FORBIDDEN)

        assertEquals(AuthOutcome.Failure(AuthError.DEVICE_NOT_ALLOWED), register())
    }

    /** A challenge that ran out, with no password to have got wrong. */
    @Test
    fun `a 404 to the challenge is an unknown error`() = runBlocking<Unit> {
        challengeAnswer = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(AuthOutcome.Failure(AuthError.UNKNOWN), register())
    }

    @Test
    fun `a 404 to the start is an unknown error`() = runBlocking<Unit> {
        startAnswer = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(AuthOutcome.Failure(AuthError.UNKNOWN), register())
    }

    /** A session that ran out, with no password to have got wrong. */
    @Test
    fun `a 404 to the finish is an unknown error`() = runBlocking<Unit> {
        finishAnswer = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(AuthOutcome.Failure(AuthError.UNKNOWN), register())
    }

    @Test
    fun `a 409 to the start is a username taken`() = runBlocking<Unit> {
        startAnswer = Result.Error(NetworkError.CONFLICT)

        assertEquals(AuthOutcome.Failure(AuthError.USERNAME_TAKEN), register())
    }

    /** The proxy's limit, which answers on the challenge's route. */
    @Test
    fun `a 429 to the challenge is too many attempts`() = runBlocking<Unit> {
        challengeAnswer = Result.Error(NetworkError.TOO_MANY_REQUESTS)

        assertEquals(AuthOutcome.Failure(AuthError.TOO_MANY_ATTEMPTS), register())
    }

    /** The device is asked what a connection that failed means, at each of the requests. */
    @Test
    fun `a request that cannot connect while offline is no internet`() = runBlocking<Unit> {
        isOnline = false

        challengeAnswer = Result.Error(NetworkError.UNREACHABLE)
        assertEquals(AuthOutcome.Failure(AuthError.NO_INTERNET), register())

        challengeAnswer = Result.Success(RegistrationChallengeResponse(attestationChallenge = CHALLENGE))
        startAnswer = Result.Error(NetworkError.UNREACHABLE)
        assertEquals(AuthOutcome.Failure(AuthError.NO_INTERNET), register())

        startAnswer = Result.Success(RegistrationStartResponse(sessionId = SESSION_ID, bytes = RESPONSE_BASE64))
        finishAnswer = Result.Error(NetworkError.UNREACHABLE)
        assertEquals(AuthOutcome.Failure(AuthError.NO_INTERNET), register())
    }

    @Test
    fun `a challenge that does not decode is an unknown error and attests nothing`() = runBlocking<Unit> {
        challengeAnswer = Result.Success(RegistrationChallengeResponse(attestationChallenge = "not base64!"))

        assertEquals(AuthOutcome.Failure(AuthError.UNKNOWN), register())
        assertEquals(listOf("challenge"), events)
    }

    @Test
    fun `a start answer whose half does not decode is an unknown error and finishes nothing`() = runBlocking<Unit> {
        startAnswer = Result.Success(RegistrationStartResponse(sessionId = SESSION_ID, bytes = "not base64!"))

        assertEquals(AuthOutcome.Failure(AuthError.UNKNOWN), register())
        assertEquals(listOf("challenge", "attest", "handshake started", "start"), events)
    }

    @Test
    fun `the answers are read as the server writes them`() = runBlocking<Unit> {
        val registration = accountRegistration(
            startRequest = { body ->
                answering("""{"session_id":"$SESSION_ID","bytes":"$RESPONSE_BASE64"}""")
                    .post<RegistrationStartResponse, RegistrationStartBody>(route = URL, body = body)
            },
            finishRequest = { body ->
                finishBodies += body

                answering("""{"short_lived_token":"short","long_lived_token":"long"}""")
                    .put<TokenResponse, RegistrationFinishBody>(route = URL, body = body)
            },
        )

        val account = assertIs<AuthOutcome.Success<RegisteredAccount>>(registration.register(USERNAME, PASSWORD)).data

        assertEquals(TOKENS, account.tokens)
        assertEquals(SESSION_ID, finishBodies.single().sessionId)
    }

    @Test
    fun `a start answer with bad JSON is an unknown error and finishes nothing`() = runBlocking<Unit> {
        MALFORMED_ANSWERS.forEach { answer ->
            val registration = accountRegistration(
                startRequest = { body ->
                    answering(answer).post<RegistrationStartResponse, RegistrationStartBody>(route = URL, body = body)
                },
            )

            assertEquals(AuthOutcome.Failure(AuthError.UNKNOWN), registration.register(USERNAME, PASSWORD), answer)
        }

        assertTrue(finishBodies.isEmpty())
    }

    @Test
    fun `a finish answer with bad JSON is an unknown error`() = runBlocking<Unit> {
        MALFORMED_ANSWERS.forEach { answer ->
            val registration = accountRegistration(
                finishRequest = { body ->
                    answering(answer).put<TokenResponse, RegistrationFinishBody>(route = URL, body = body)
                },
            )

            assertEquals(AuthOutcome.Failure(AuthError.UNKNOWN), registration.register(USERNAME, PASSWORD), answer)
        }
    }

    @Test
    fun `a handshake that will not start is a crypto error and sends nothing`() = runBlocking<Unit> {
        startFailure = AuthenticationException.StartingRegistrationFailed()

        assertEquals(AuthOutcome.Failure(AuthError.CRYPTO_ERROR), register())
        assertEquals(listOf("challenge", "attest", "handshake started"), events)
    }

    @Test
    fun `a handshake that will not finish is a crypto error and sends nothing`() = runBlocking<Unit> {
        finishFailure = AuthenticationException.ClientRegistrationFinalizationFailed()

        assertEquals(AuthOutcome.Failure(AuthError.CRYPTO_ERROR), register())
        assertEquals(listOf("challenge", "attest", "handshake started", "start", "handshake finished"), events)
    }

    /** The caller going away, which is not an answer of any kind. */
    @Test
    fun `a cancellation passes through`() = runBlocking<Unit> {
        val registration = accountRegistration(startRequest = { throw CancellationException("The caller went away") })

        assertFailsWith<CancellationException> { registration.register(USERNAME, PASSWORD) }
    }

    private suspend fun register(): AuthOutcome<RegisteredAccount> =
        accountRegistration().register(username = USERNAME, password = PASSWORD)

    private fun accountRegistration(
        startRequest: suspend (RegistrationStartBody) -> Result<RegistrationStartResponse, NetworkError> = ::start,
        finishRequest: suspend (RegistrationFinishBody) -> Result<TokenResponse, NetworkError> = ::finish,
    ) = AccountRegistration(
        askChallenge = {
            events += "challenge"
            challengeAnswer
        },
        deviceAttester = AttesterStandIn(),
        newHandshake = { HandshakeStandIn() },
        hashUsername = { username -> "digest of $username" },
        start = startRequest,
        finish = finishRequest,
        isOnline = { isOnline },
    )

    private fun start(body: RegistrationStartBody): Result<RegistrationStartResponse, NetworkError> {
        events += "start"
        startBodies += body

        return startAnswer
    }

    private fun finish(body: RegistrationFinishBody): Result<TokenResponse, NetworkError> {
        events += "finish"
        finishBodies += body

        return finishAnswer
    }

    /** A client answering any request with [answer], reading it as `DuckCountClient` does. */
    private fun answering(answer: String): Client = object : Client() {
        override val httpClient = HttpClient(
            MockEngine {
                respond(
                    content = answer,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(json = Json { ignoreUnknownKeys = true })
            }
        }
    }

    private inner class AttesterStandIn : DeviceAttester {

        override suspend fun attest(challenge: ByteArray): List<String>? {
            events += "attest"
            attested += challenge

            return attestation
        }
    }

    /** Stands in for the library's handshake, which fails only when a test says so. */
    private inner class HandshakeStandIn : RegistrationHandshake {

        override fun start(password: String): ByteArray {
            events += "handshake started"
            startFailure?.let { throw it }

            return REQUEST
        }

        override fun finish(registrationResponseBytes: ByteArray, password: String): FinishedRegistrationHandshake {
            events += "handshake finished"
            finishedWith += registrationResponseBytes
            finishingThread = Thread.currentThread()
            finishFailure?.let { throw it }

            return FinishedRegistrationHandshake(
                message = MESSAGE,
                sealedRootKey = EncryptedFieldDto(ciphertext = "sealed", nonce = "nonce"),
                rootKey = ROOT_KEY,
            )
        }
    }
}
