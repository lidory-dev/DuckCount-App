package app.lidory.duckcount.authentication

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import PendingProfile
import UsernameInputError
import android.util.Log
import app.lidory.duckcount.PasswordRequirements
import app.lidory.duckcount.ProfileLimits
import app.lidory.duckcount.hashLoginUsername
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.usernameMaximumLength
import authenticationUtils.PendingRegistration
import authenticationUtils.clearKeyPackagesPublishedAt
import authenticationUtils.clearPendingRegistration
import authenticationUtils.clearTokens
import authenticationUtils.setAuthenticatedUserUsername
import authenticationUtils.setAuthenticatedUserUuid
import authenticationUtils.setContactsToRestore
import authenticationUtils.setMemberProfilesToRestore
import authenticationUtils.setHasPasswordBeenChecked
import authenticationUtils.setPendingRegistration
import authenticationUtils.setRootKey
import authenticationUtils.setTokens
import database.data.remote.LoginFinishBody
import database.data.remote.LoginStartBody
import database.data.remote.LoginStartResponse
import database.data.repository.UserRepository
import discoveryUsername.DiscoveryUsernameRepository
import mls.KeyPackageError
import mls.KeyPackageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mls.MlsIdentityError
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.HaveIBeenPwnedClient
import org.json.JSONException
import org.json.JSONObject
import profile.ProfileError
import profile.ProfileRepository
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.EncryptedField
import uniffi.lib_duckcount.Login
import util.NetworkError
import util.Result
import util.onError
import vault.VaultError
import app.lidory.duckcount.UsernameError as SharedUsernameError
import app.lidory.duckcount.validateUsername as validateUsernameShared

private const val TAG = "AuthCallBacks"

class AuthenticationCallBacksImpl(
    val userRepository: UserRepository,
    private val networkConnectivity: NetworkConnectivity,
    private val duckCountClient: DuckCountClient,
    private val haveIBeenPwnedClient: HaveIBeenPwnedClient,
    private val profileRepository: ProfileRepository,
    private val discoveryUsernameRepository: DiscoveryUsernameRepository,
    private val keyPackageRepository: KeyPackageRepository,
    private val accountRegistration: AccountRegistration,
) : AuthenticationCallBacks {

    override val maxUsernameLength: Int = usernameMaximumLength()

    override val maxFullNameLength: Int = ProfileLimits.MAX_FULL_NAME_LENGTH

    override val passwordStrength: Int = PasswordRequirements.MINIMUM_PASSWORD_STRENGTH

    override suspend fun passwordLeakAmount(password: String): AuthOutcome<Int> =
        when (val result = haveIBeenPwnedClient.passwordLeakCount(password = password)) {
            is Result.Success -> AuthOutcome.Success(result.data)

            is Result.Error -> {
                Log.w(TAG, "passwordLeakAmount failed: ${result.error}")

                AuthOutcome.Failure(result.error.toAuthError())
            }
        }

    override suspend fun registration(username: String, password: String): AuthOutcome<String> {
        val registered = when (val outcome = accountRegistration.register(username = username, password = password)) {
            is AuthOutcome.Success -> outcome.data
            is AuthOutcome.Failure -> return outcome
        }

        val shortLivedToken = registered.tokens.shortLivedToken

        // The server carries the new user's UUID in the short-lived token's `sub`.
        val uuid = shortLivedToken.jwtClaim(claim = "sub")
            ?: run {
                Log.e(TAG, "registration: could not read uuid from short-lived token")
                return AuthOutcome.Failure(AuthError.UNKNOWN)
            }

        setAuthenticatedUserUuid(uuid)
        setAuthenticatedUserUsername(username = username)
        setRootKey(registered.rootKey)

        if (!storeTokens(shortLivedToken = shortLivedToken, longLivedToken = registered.tokens.longLivedToken)) {
            return AuthOutcome.Failure(AuthError.UNKNOWN)
        }

        return AuthOutcome.Success(uuid)
    }

    override suspend fun login(username: String, password: String): AuthOutcome<Unit> {
        val login = Login()

        try {
            val startResult = duckCountClient.post<LoginStartResponse, LoginStartBody>(
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
                is Result.Success -> startResult.data.sessionId to startResult.data.credentialResponseBytes

                is Result.Error -> {
                    Log.w(TAG, "login start failed: ${startResult.error}")
                    return AuthOutcome.Failure(startResult.error.toAuthError())
                }
            }

            val finalization = withContext(Dispatchers.Default) {
                login.finish(
                    credentialResponseBytes = responseBytes.decodeBase64ToByteArray(),
                    password = password,
                ).message
            }

            val finishResult = duckCountClient.put<String, LoginFinishBody>(
                route = DuckCountRoutes.Auth.SESSIONS,
                body = LoginFinishBody(
                    sessionId = sessionId,
                    credentialFinalizationBytes = finalization.toBase64(),
                ),
            )

            return when (finishResult) {
                is Result.Success -> {
                    val body = JSONObject(finishResult.data)
                    val shortLivedToken = body.getString("short_lived_token")
                    val longLivedToken = body.getString("long_lived_token")

                    // Before anything that lets a sync run. The server keeps no record of
                    // who the user's contacts are, so the vault is the only place this device
                    // gets them back from, and syncs keep trying until it has.
                    setContactsToRestore()

                    // Likewise the keys the user's groups handed over, which name the members
                    // who are not contacts.
                    setMemberProfilesToRestore()

                    // A session starts with nothing published, whatever a publication that
                    // finished as the last one was ending recorded, see
                    // [KeyPackageRepository.publishIfDue].
                    clearKeyPackagesPublishedAt()

                    // The server carries the user's UUID in the short-lived token's `sub`.
                    val uuid = shortLivedToken.jwtClaim(claim = "sub")
                    uuid?.let {
                        setAuthenticatedUserUuid(uuid)
                    } ?: Log.e(TAG, "login: could not read uuid from short-lived token")

                    setAuthenticatedUserUsername(username = username)

                    body.optJSONObject("root_key")?.let { sealedRootKey ->
                        setRootKey(
                            login.unsealRootKey(
                                encryptedRootKey = EncryptedField.fromParts(
                                    ciphertext = sealedRootKey.getString("ciphertext")
                                        .decodeBase64ToByteArray(),
                                    nonce = sealedRootKey.getString("nonce")
                                        .decodeBase64ToByteArray(),
                                ),
                            ),
                        )
                    } ?: Log.w(TAG, "login: the server returned no root key")

                    // Before the calls below, which need it to authenticate.
                    if (!storeTokens(shortLivedToken = shortLivedToken, longLivedToken = longLivedToken)) {
                        return AuthOutcome.Failure(AuthError.UNKNOWN)
                    }

                    uuid?.let {
                        profileRepository.restoreProfile(userUuid = it, username = username)
                            .onError { error ->
                                Log.w(TAG, "login: could not restore the profile: $error")
                            }
                    }

                    discoveryUsernameRepository.syncDiscoveryUsername().onError { error ->
                        Log.w(TAG, "login: could not restore the discovery username: $error")
                    }

                    // Replaces the key packages the server holds, whose secrets a previous
                    // log-out or install may have taken with it: nobody could open those.
                    // A failure leaves them due, and the dashboard publishes them, see
                    // [KeyPackageRepository.publishIfDue].
                    keyPackageRepository.publish().onError { error ->
                        Log.w(TAG, "login: could not publish the key packages: $error")
                    }

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

    override suspend fun insertUser(
        uuid: String,
        username: String,
        fullName: String,
        hasPasswordBeenChecked: Boolean,
    ): AuthOutcome<Unit> {
        setHasPasswordBeenChecked(hasPasswordBeenChecked)

        val upload = profileRepository.uploadProfile(
            userUuid = uuid,
            username = username,
            fullName = fullName,
        )

        if (upload is Result.Error) {
            Log.w(TAG, "insertUser: could not upload the profile: ${upload.error}")

            // The marker stays, so the user comes back to this step rather than to an
            // account with no profile anywhere.
            return AuthOutcome.Failure(
                upload.error.toAuthError(),
            )
        }

        // Without key packages nobody can send the new account a contact request.
        val publication = keyPackageRepository.publish()

        if (publication is Result.Error) {
            Log.w(TAG, "insertUser: could not publish the key packages: ${publication.error}")

            // The marker stays here too, and coming back to this step publishes again.
            return AuthOutcome.Failure(
                publication.error.toAuthError(),
            )
        }

        // Account set up, drop the saved "resume registration" marker.
        clearPendingRegistration()

        return AuthOutcome.Success(Unit)
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

    /**
     * Stores the session's tokens, and says whether it could.
     *
     * Last of what a login or a registration stores: the long-lived token is what marks a
     * session, see [isLoggedIn], and stored ahead of the rest, a failure or the process ending
     * in between would leave the next launch a session without the user it belongs to.
     *
     * A session whose tokens this device did not keep is not one either, so a failure clears
     * them, the long-lived one being written first and able to go in alone. The device locking
     * in between is the usual reason, see [setTokens].
     */
    private suspend fun storeTokens(shortLivedToken: String, longLivedToken: String): Boolean =
        try {
            setTokens(shortLivedToken = shortLivedToken, longLivedToken = longLivedToken)

            true
        } catch (exception: CancellationException) {
            // The caller going away, which the catch below would otherwise swallow.
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "could not store the session's tokens", exception)

            clearTokens()

            false
        }

    private fun SharedUsernameError.toUsernameInputError(): UsernameInputError = when (this) {
        SharedUsernameError.EMPTY -> UsernameInputError.EMPTY
        SharedUsernameError.MUST_START_WITH_LETTER -> UsernameInputError.MUST_START_WITH_LETTER
        SharedUsernameError.INVALID_CHARACTER -> UsernameInputError.INVALID_CHARACTER
        SharedUsernameError.TOO_LONG -> UsernameInputError.TOO_LONG
    }

    /**
     * Maps a failed profile upload to a user-facing [AuthError].
     *
     * The vault half is folded in: to somebody finishing a registration, "the key could
     * not be written" and "the profile could not be sent" are the same unfinished step.
     */
    private fun ProfileError.toAuthError(): AuthError = when (this) {
        is ProfileError.Network -> error.toAuthError()

        is ProfileError.Vault -> error.toAuthError()

        ProfileError.FullNameTooLong -> AuthError.FULL_NAME_TOO_LONG

        // Neither can happen on the way out: NotStored is a read outcome, and a field
        // this device just sealed is one it can seal.
        ProfileError.NotStored,
        ProfileError.Crypto,
            -> AuthError.UNKNOWN
    }

    /**
     * Maps a failed key package publication to a user-facing [AuthError].
     *
     * The vault and the library are folded in the same way as for the profile: to
     * somebody finishing a registration, they are all the same unfinished step.
     */
    private fun KeyPackageError.toAuthError(): AuthError = when (this) {
        is KeyPackageError.Network -> error.toAuthError()

        is KeyPackageError.Identity -> when (val identityError = error) {
            is MlsIdentityError.Vault -> identityError.error.toAuthError()

            // The session was set up moments ago by the registration that led here, so
            // its absence is not something the user can be asked to fix.
            MlsIdentityError.MissingSession -> AuthError.CRYPTO_ERROR

            MlsIdentityError.Crypto -> AuthError.CRYPTO_ERROR
        }

        KeyPackageError.Crypto -> AuthError.CRYPTO_ERROR
    }

    private fun VaultError.toAuthError(): AuthError = when (this) {
        is VaultError.Network -> error.toAuthError()

        // The root key was set moments ago by the registration that led here, so its
        // absence is not something the user can be asked to fix.
        VaultError.MissingRootKey,
        VaultError.Crypto,
        VaultError.MalformedKeyRing,
        VaultError.MalformedContactBook,
        VaultError.MalformedMemberProfileBook,
        VaultError.OrphanedEntry,
        VaultError.Contended,
            -> AuthError.CRYPTO_ERROR
    }

    private fun NetworkError.toAuthError(): AuthError = toAuthError(isOnline = networkConnectivity.isOnline())
}
