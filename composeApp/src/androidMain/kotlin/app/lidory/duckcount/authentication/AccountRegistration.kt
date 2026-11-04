package app.lidory.duckcount.authentication

import AuthError
import AuthOutcome
import android.util.Log
import app.lidory.duckcount.hashLoginUsername
import app.lidory.duckcount.networking.NetworkConnectivity
import database.data.remote.EncryptedFieldDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.TokenResponse
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.Registration
import util.NetworkError
import util.Result

private const val TAG = "AuthCallBacks"

/**
 * Creates the user's account on the server, up to what is left to store on this device, see
 * [RegisteredAccount].
 *
 * In this order:
 * 1. The server hands out a challenge.
 * 2. The phone attests over the challenge's bytes, see [DeviceAttester]. One that cannot stops
 *    everything here, before the server has heard of the username.
 * 3. The registration starts, with the username and the attestation, the challenge going back as
 *    it came.
 * 4. Argon2 runs, on this device, and the registration finishes, which creates the account.
 *
 * [askChallenge], [start] and [finish] are `POST /auth/registrations/challenges`,
 * `POST /auth/registrations` and `PUT /auth/registrations`, [newHandshake] runs the library's
 * [Registration], [hashUsername] is [hashLoginUsername] and [isOnline] is
 * [NetworkConnectivity.isOnline], or what a test stands in for any of them.
 */
class AccountRegistration internal constructor(
    private val askChallenge: suspend () -> Result<RegistrationChallengeResponse, NetworkError>,
    private val deviceAttester: DeviceAttester,
    private val newHandshake: () -> RegistrationHandshake,
    private val hashUsername: (username: String) -> String,
    private val start: suspend (body: RegistrationStartBody) -> Result<RegistrationStartResponse, NetworkError>,
    private val finish: suspend (body: RegistrationFinishBody) -> Result<TokenResponse, NetworkError>,
    private val isOnline: () -> Boolean,
) {
    constructor(
        duckCountClient: DuckCountClient,
        deviceAttester: DeviceAttester,
        networkConnectivity: NetworkConnectivity,
    ) : this(
        askChallenge = {
            // A `Unit` goes out as no body at all.
            duckCountClient.post<RegistrationChallengeResponse, Unit>(
                route = DuckCountRoutes.Auth.REGISTRATION_CHALLENGES,
                body = Unit,
            )
        },
        deviceAttester = deviceAttester,
        newHandshake = { LibraryHandshake() },
        hashUsername = ::hashLoginUsername,
        start = { body ->
            duckCountClient.post<RegistrationStartResponse, RegistrationStartBody>(
                route = DuckCountRoutes.Auth.REGISTRATIONS,
                body = body,
            )
        },
        finish = { body ->
            duckCountClient.put<TokenResponse, RegistrationFinishBody>(
                route = DuckCountRoutes.Auth.REGISTRATIONS,
                body = body,
            )
        },
        isOnline = networkConnectivity::isOnline,
    )

    /**
     * Creates the account of [username] with [password]. An answer that does not read as the one
     * asked for comes out of the client as an error like any other, and reads as an unknown one.
     */
    suspend fun register(username: String, password: String): AuthOutcome<RegisteredAccount> {
        val handshake = newHandshake()

        try {
            // First of all: the server says nothing about a username without an attestation over
            // a challenge of its own, see [DeviceAttester].
            val attestationChallenge = when (val challengeResult = askChallenge()) {
                is Result.Success -> challengeResult.data.attestationChallenge

                is Result.Error -> {
                    Log.w(TAG, "registration challenge failed: ${challengeResult.error}")
                    return AuthOutcome.Failure(challengeResult.error.toRegistrationError(isOnline = isOnline()))
                }
            }

            val certificateChain = deviceAttester.attest(challenge = attestationChallenge.decodeBase64ToByteArray())
                ?: return AuthOutcome.Failure(AuthError.DEVICE_CHECK_FAILED)

            val startResult = start(
                RegistrationStartBody(
                    bytes = handshake.start(password).toBase64(),
                    // The server only ever knows the digest of the username, never the
                    // username itself.
                    username = hashUsername(username),
                    attestationChallenge = attestationChallenge,
                    attestation = DeviceAttestationBody(
                        platform = ATTESTATION_PLATFORM,
                        certificateChain = certificateChain,
                    ),
                ),
            )

            // The username is not repeated when finishing: the server kept it, keyed by
            // the session id it just handed out.
            val (sessionId, responseBytes) = when (startResult) {
                is Result.Success -> startResult.data.sessionId to startResult.data.bytes

                is Result.Error -> {
                    Log.w(TAG, "registration start failed: ${startResult.error}")
                    return AuthOutcome.Failure(startResult.error.toRegistrationError(isOnline = isOnline()))
                }
            }

            val finished = withContext(Dispatchers.Default) {
                handshake.finish(
                    registrationResponseBytes = responseBytes.decodeBase64ToByteArray(),
                    password = password,
                )
            }

            val finishResult = finish(
                RegistrationFinishBody(
                    sessionId = sessionId,
                    messageBytes = finished.message.toBase64(),
                    rootKey = finished.sealedRootKey,
                ),
            )

            return when (finishResult) {
                is Result.Success -> AuthOutcome.Success(
                    RegisteredAccount(tokens = finishResult.data, rootKey = finished.rootKey),
                )

                is Result.Error -> {
                    Log.w(TAG, "registration finish failed: ${finishResult.error}")
                    AuthOutcome.Failure(finishResult.error.toRegistrationError(isOnline = isOnline()))
                }
            }
        } catch (e: AuthenticationException) {
            Log.e(TAG, "registration OPAQUE error", e)
            return AuthOutcome.Failure(AuthError.CRYPTO_ERROR)
        } catch (e: IllegalArgumentException) {
            // Malformed base64 in the server response.
            Log.e(TAG, "registration base64 decode error", e)
            return AuthOutcome.Failure(AuthError.UNKNOWN)
        }
    }
}

/** What a registration leaves to store on this device: the session, and the vault's root key. */
class RegisteredAccount(
    val tokens: TokenResponse,
    val rootKey: ByteArray,
)

/**
 * The handshake of a registration, as [AccountRegistration] runs it: the library's [Registration]
 * in its own shapes, see [LibraryHandshake], but for the object the library finishes it with,
 * which only the library can make, so that a test can stand in for it.
 */
internal interface RegistrationHandshake {

    /** Starts the handshake, see [Registration.start]. */
    fun start(password: String): ByteArray

    /** Finishes the handshake, see [Registration.finish], which derives a key with Argon2. */
    fun finish(registrationResponseBytes: ByteArray, password: String): FinishedRegistrationHandshake
}

/** A finished [RegistrationHandshake]: the [message] and [sealedRootKey] to send, and [rootKey]. */
internal class FinishedRegistrationHandshake(
    val message: ByteArray,
    val sealedRootKey: EncryptedFieldDto,
    val rootKey: ByteArray,
)

/** The [RegistrationHandshake] the library runs. */
private class LibraryHandshake : RegistrationHandshake {

    private val registration = Registration()

    override fun start(password: String): ByteArray = registration.start(password)

    override fun finish(registrationResponseBytes: ByteArray, password: String): FinishedRegistrationHandshake {
        val finished = registration.finish(
            registrationResponseBytes = registrationResponseBytes,
            password = password,
        )

        val sealedRootKey = finished.encryptedRootKey()

        return FinishedRegistrationHandshake(
            message = finished.message(),
            sealedRootKey = EncryptedFieldDto(
                ciphertext = sealedRootKey.ciphertext().toBase64(),
                nonce = sealedRootKey.nonce().toBase64(),
            ),
            rootKey = finished.rootKey(),
        )
    }
}
