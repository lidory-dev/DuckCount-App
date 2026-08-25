package app.lidory.duckcount.authentication

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import PendingProfile
import UsernameInputError
import android.util.Log
import app.lidory.duckcount.PasswordRequirements
import app.lidory.duckcount.hashLoginUsername
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.usernameMaximumLength
import authenticationUtils.PendingRegistration
import authenticationUtils.clearPendingRegistration
import authenticationUtils.setAuthenticatedUserUsername
import authenticationUtils.setAuthenticatedUserUuid
import authenticationUtils.setHasPasswordBeenChecked
import authenticationUtils.setPendingRegistration
import authenticationUtils.setTokens
import database.data.repository.UserRepository
import database.domain.User
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.HaveIBeenPwnedClient
import networking.HaveIBeenPwnedRoutes
import org.json.JSONException
import org.json.JSONObject
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.Login
import uniffi.lib_duckcount.Registration
import util.NetworkError
import util.Result
import app.lidory.duckcount.UsernameError as SharedUsernameError
import app.lidory.duckcount.validateUsername as validateUsernameShared

private const val TAG = "AuthCallBacks"

class AuthenticationCallBacksImpl(
    val userRepository: UserRepository,
    private val networkConnectivity: NetworkConnectivity,
    private val duckCountClient: DuckCountClient,
    private val haveIBeenPwnedClient: HaveIBeenPwnedClient,
) : AuthenticationCallBacks {

    override val maxUsernameLength: Int = usernameMaximumLength()

    override val passwordStrength: Int = PasswordRequirements.MINIMUM_PASSWORD_STRENGTH

    override suspend fun passwordLeakAmount(password: String): AuthOutcome<Int> {
        val sha1 = password.sha1().uppercase()

        val result = haveIBeenPwnedClient.get<String>(
            route = HaveIBeenPwnedRoutes.Password.CHECK,
            pathParams = mapOf("sha1FiveFirstCharacters" to sha1.take(5)),
        )

        return when (result) {
            is Result.Success -> {
                // The range API returns lines of `SUFFIX:COUNT`; find the line for our suffix.
                val suffix = sha1.drop(5)
                val count = result.data.lineSequence()
                    .firstOrNull { it.startsWith(prefix = suffix, ignoreCase = true) }
                    ?.substringAfter(delimiter = ":")
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0

                AuthOutcome.Success(count)
            }

            is Result.Error -> {
                Log.w(TAG, "passwordLeakAmount failed: ${result.error}")

                AuthOutcome.Failure(result.error.toAuthError())
            }
        }
    }

    override suspend fun registration(username: String, password: String): AuthOutcome<String> {
        val registration = Registration()

        try {
            val startResult = duckCountClient.post<String, RegistrationStartBody>(
                route = DuckCountRoutes.Auth.REGISTRATIONS,
                body = RegistrationStartBody(
                    bytes = registration.start(password).toBase64(),
                    // The server only ever knows the digest of the username, never the
                    // username itself.
                    username = hashLoginUsername(username = username),
                ),
            )

            // The username is not repeated when finishing: the server kept it, keyed by
            // the session id it just handed out.
            val (sessionId, responseBytes) = when (startResult) {
                is Result.Success -> JSONObject(startResult.data).let {
                    it.getString("session_id") to it.getString("bytes")
                }

                is Result.Error -> {
                    Log.w(TAG, "registration start failed: ${startResult.error}")
                    return AuthOutcome.Failure(startResult.error.toAuthError())
                }
            }

            val finishResult = duckCountClient.put<String, RegistrationFinishBody>(
                route = DuckCountRoutes.Auth.REGISTRATIONS,
                body = RegistrationFinishBody(
                    sessionId = sessionId,
                    // TODO: The finish result also carries the OPAQUE export key, which is
                    //  what client-side encryption keys should be derived from.
                    messageBytes = registration.finish(
                        registrationResponseBytes = responseBytes.decodeBase64ToByteArray(),
                        password = password,
                    ).message.toBase64(),
                    // STUB: a real public key is not generated (no Rust
                    // keypair function is exposed). Kept as a placeholder; the server stores it
                    // as-is. Swap for the real vault public key later.
                    publicKey = PLACEHOLDER_PUBLIC_KEY.toByteArray().toBase64(),
                ),
            )

            return when (finishResult) {
                is Result.Success -> {
                    val body = JSONObject(finishResult.data)
                    val shortLivedToken = body.getString("short_lived_token")
                    val longLivedToken = body.getString("long_lived_token")

                    // The server carries the new user's UUID in the short-lived token's `sub`.
                    val uuid = shortLivedToken.jwtClaim(claim = "sub")
                        ?: run {
                            Log.e(TAG, "registration: could not read uuid from short-lived token")
                            return AuthOutcome.Failure(AuthError.UNKNOWN)
                        }

                    setTokens(
                        shortLivedToken = shortLivedToken,
                        longLivedToken = longLivedToken,
                    )
                    setAuthenticatedUserUuid(uuid)
                    setAuthenticatedUserUsername(username = username)

                    AuthOutcome.Success(uuid)
                }

                is Result.Error -> {
                    Log.w(TAG, "registration finish failed: ${finishResult.error}")
                    AuthOutcome.Failure(finishResult.error.toAuthError())
                }
            }
        } catch (e: AuthenticationException) {
            Log.e(TAG, "registration OPAQUE error", e)
            return AuthOutcome.Failure(AuthError.CRYPTO_ERROR)
        } catch (e: JSONException) {
            Log.e(TAG, "registration response parse error", e)
            return AuthOutcome.Failure(AuthError.UNKNOWN)
        } catch (e: IllegalArgumentException) {
            // Malformed base64 in the server response.
            Log.e(TAG, "registration base64 decode error", e)
            return AuthOutcome.Failure(AuthError.UNKNOWN)
        }
    }

    override suspend fun login(username: String, password: String): AuthOutcome<Unit> {
        val login = Login()

        try {
            val startResult = duckCountClient.post<String, LoginStartBody>(
                route = DuckCountRoutes.Auth.SESSIONS,
                body = LoginStartBody(
                    credentialRequestBytes = login.start(password).toBase64(),
                    // Hashed the same way registration hashed it, otherwise the account
                    // simply cannot be found.
                    username = hashLoginUsername(username = username),
                ),
            )

            // The username is not repeated when finishing: the server kept track of who is
            // logging in, keyed by the session id it just handed out.
            val (sessionId, responseBytes) = when (startResult) {
                is Result.Success -> JSONObject(startResult.data).let {
                    it.getString("session_id") to it.getString("credential_response_bytes")
                }

                is Result.Error -> {
                    Log.w(TAG, "login start failed: ${startResult.error}")
                    return AuthOutcome.Failure(startResult.error.toAuthError())
                }
            }

            val finishResult = duckCountClient.put<String, LoginFinishBody>(
                route = DuckCountRoutes.Auth.SESSIONS,
                body = LoginFinishBody(
                    sessionId = sessionId,
                    // TODO: The finish result also carries the OPAQUE export key, which is
                    //  what client-side encryption keys should be derived from.
                    credentialFinalizationBytes = login.finish(
                        credentialResponseBytes = responseBytes.decodeBase64ToByteArray(),
                        password = password,
                    ).message.toBase64(),
                ),
            )

            return when (finishResult) {
                is Result.Success -> {
                    val body = JSONObject(finishResult.data)
                    val shortLivedToken = body.getString("short_lived_token")
                    val longLivedToken = body.getString("long_lived_token")

                    setTokens(shortLivedToken = shortLivedToken, longLivedToken = longLivedToken)

                    // The server carries the user's UUID in the short-lived token's `sub`.
                    val uuid = shortLivedToken.jwtClaim(claim = "sub")
                    uuid?.let {
                        setAuthenticatedUserUuid(uuid)
                    } ?: Log.e(TAG, "login: could not read uuid from short-lived token")

                    setAuthenticatedUserUsername(username = username)

                    AuthOutcome.Success(Unit)
                }

                is Result.Error -> {
                    Log.w(TAG, "login finish failed: ${finishResult.error}")
                    AuthOutcome.Failure(finishResult.error.toAuthError())
                }
            }
        } catch (e: AuthenticationException.ClientLoginFinalizationFailed) {
            // With OPAQUE, a wrong password (or unknown user) surfaces as the client-side finish
            // step failing, we present it as invalid credentials rather than a generic crypto error.
            Log.w(TAG, "login finalization failed (likely wrong credentials)", e)
            return AuthOutcome.Failure(AuthError.INVALID_CREDENTIALS)
        } catch (e: AuthenticationException) {
            Log.e(TAG, "login OPAQUE error", e)
            return AuthOutcome.Failure(AuthError.CRYPTO_ERROR)
        } catch (e: JSONException) {
            Log.e(TAG, "login response parse error", e)
            return AuthOutcome.Failure(AuthError.UNKNOWN)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "login base64 decode error", e)
            return AuthOutcome.Failure(AuthError.UNKNOWN)
        }
    }

    override fun validateUsername(username: String): UsernameInputError? {
        return validateUsernameShared(username = username)?.toUsernameInputError()
    }

    override suspend fun insertUser(uuid: String, username: String, fullName: String, hasPasswordBeenChecked: Boolean) {
        setHasPasswordBeenChecked(hasPasswordBeenChecked)

        userRepository.insert(
            User(
                uuid = uuid,
                username = username,
                fullName = fullName,
            ),
        )

        // Profile completed, drop the saved "resume registration" marker.
        clearPendingRegistration()
    }

    override suspend fun markPendingRegistration(uuid: String, username: String, hasPasswordBeenChecked: Boolean) {
        setPendingRegistration(
            PendingRegistration(uuid = uuid, username = username, hasPasswordBeenChecked = hasPasswordBeenChecked),
        )
    }

    override suspend fun getPendingRegistration(): PendingProfile? =
        authenticationUtils.getPendingRegistration()?.let {
            PendingProfile(uuid = it.uuid, username = it.username, hasPasswordBeenChecked = it.hasPasswordBeenChecked)
        }

    override suspend fun getShortLivedToken(): String? =
        authenticationUtils.getShortLivedToken()

    override suspend fun getLongLivedToken(): String? =
        authenticationUtils.getLongLivedToken()

    // A stored refresh (long-lived) token means there's a session to resume. The short-lived
    // token expires after 20 min, and a network check (e.g. GET /vault) would wrongly report
    // "not logged in" when it's merely expired, the vault doesn't exist yet, or we're offline.
    override suspend fun isLoggedIn(): Boolean =
        getLongLivedToken()?.isNotBlank() ?: false

    private fun SharedUsernameError.toUsernameInputError(): UsernameInputError = when (this) {
        SharedUsernameError.EMPTY -> UsernameInputError.EMPTY
        SharedUsernameError.MUST_START_WITH_LETTER -> UsernameInputError.MUST_START_WITH_LETTER
        SharedUsernameError.INVALID_CHARACTER -> UsernameInputError.INVALID_CHARACTER
        SharedUsernameError.TOO_LONG -> UsernameInputError.TOO_LONG
    }

    /**
     * Maps a transport-level [NetworkError] to a user-facing [AuthError]. The "couldn't connect"
     * case ([NetworkError.UNREACHABLE]) is resolved against real device connectivity, so airplane
     * mode reads as "no internet" even when the base URL is a bare IP (no DNS step to fail on).
     */
    private fun NetworkError.toAuthError(): AuthError = when (this) {
        NetworkError.NO_INTERNET -> AuthError.NO_INTERNET
        NetworkError.UNREACHABLE ->
            if (networkConnectivity.isOnline()) AuthError.UNREACHABLE else AuthError.NO_INTERNET

        NetworkError.CONFLICT -> AuthError.USERNAME_TAKEN
        // OPAQUE login hides user existence: both "unknown user" (404) and "unauthorized" read as
        // invalid credentials to the user.
        NetworkError.UNAUTHORIZED,
        NetworkError.NOT_FOUND,
            -> AuthError.INVALID_CREDENTIALS

        NetworkError.REQUEST_TIMEOUT -> AuthError.TIMEOUT
        NetworkError.SERVER_ERROR -> AuthError.SERVER_ERROR
        NetworkError.BAD_REQUEST,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        NetworkError.TOO_MANY_REQUESTS,
        NetworkError.SERIALIZATION,
        NetworkError.UNKNOWN,
            -> AuthError.UNKNOWN
    }

    companion object {
        // Placeholder until MLS is implemented client-side.
        private const val PLACEHOLDER_PUBLIC_KEY = "public_key"
    }
}
