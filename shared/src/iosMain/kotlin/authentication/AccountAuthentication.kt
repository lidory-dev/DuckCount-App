package authentication

import app.lidory.duckcount.ExternalLinks
import app.lidory.duckcount.PasswordRequirements
import app.lidory.duckcount.ProfileLimits
import app.lidory.duckcount.UsernameError
import app.lidory.duckcount.hashLoginUsername
import app.lidory.duckcount.usernameMaximumLength
import app.lidory.duckcount.validateUsername as validateUsernameShared
import authenticationUtils.PendingRegistration
import authenticationUtils.clearKeyPackagesPublishedAt
import authenticationUtils.clearPendingRegistration
import authenticationUtils.clearTokens
import authenticationUtils.getLongLivedToken
import authenticationUtils.setAuthenticatedUserUsername
import authenticationUtils.setAuthenticatedUserUuid
import authenticationUtils.setContactsToRestore
import authenticationUtils.setHasPasswordBeenChecked
import authenticationUtils.setMemberProfilesToRestore
import authenticationUtils.setPendingRegistration
import authenticationUtils.setRootKey
import authenticationUtils.setTokens
import database.data.remote.EncryptedFieldDto
import database.data.remote.LoginFinishBody
import database.data.remote.LoginStartBody
import database.data.remote.LoginStartResponse
import discoveryUsername.DiscoveryUsernameRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mls.KeyPackageError
import mls.KeyPackageRepository
import mls.MlsIdentityError
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.HaveIBeenPwnedClient
import networking.TokenResponse
import profile.ProfileError
import profile.ProfileRepository
import uniffi.lib_duckcount.AuthenticationException
import uniffi.lib_duckcount.EncryptedField
import uniffi.lib_duckcount.Login
import uniffi.lib_duckcount.Registration
import util.NetworkError
import util.Result
import util.onError
import vault.VaultError
import kotlin.io.encoding.Base64

private const val TAG = "AccountAuthentication"

/**
 * What the iOS authentication screens run: logging in, registering and setting up the profile
 * that finishes a registration, in the same order and with the same stores as Android's
 * `AuthenticationCallBacksImpl` and `AccountRegistration`, which live in the Android app.
 *
 * Registering attests the device first, see [AppAttester], the server saying nothing about a
 * username without an attestation it accepts, over a challenge of its own.
 */
class AccountAuthentication(
    private val duckCountClient: DuckCountClient,
    private val haveIBeenPwnedClient: HaveIBeenPwnedClient,
    private val profileRepository: ProfileRepository,
    private val discoveryUsernameRepository: DiscoveryUsernameRepository,
    private val keyPackageRepository: KeyPackageRepository,
    private val networkConnectivity: NetworkConnectivity,
    private val appAttester: AppAttester,
) {

    /** Longest username `lib-duckcount` accepts, for the "too long" message to count against. */
    val maxUsernameLength: Int = usernameMaximumLength()

    /** Longest full name the server takes, in UTF-16 units, see [ProfileLimits]. */
    val maxFullNameLength: Int = ProfileLimits.MAX_FULL_NAME_LENGTH

    /** Shortest password a registration takes. */
    val minimumPasswordLength: Int = PasswordRequirements.MINIMUM_PASSWORD_STRENGTH

    /** The terms of use creating an account accepts, which the registration form links to. */
    val termsOfUseLink: String = ExternalLinks.TERMS_OF_USE

    /** The privacy policy the registration form links to beside the terms of use. */
    val privacyPolicyLink: String = ExternalLinks.PRIVACY_POLICY

    /** Why [username] cannot be used as typed, or null when the library takes it. */
    fun validateUsername(username: String): UsernameError? = validateUsernameShared(username = username)

    /**
     * How many times [password] turns up in known breaches (Have I Been Pwned), or null if that
     * could not be found out, which lets a registration go ahead unchecked.
     */
    suspend fun passwordLeakCount(password: String): Int? =
        when (val result = haveIBeenPwnedClient.passwordLeakCount(password = password)) {
            is Result.Success -> result.data

            is Result.Error -> {
                warn("passwordLeakCount failed: ${result.error}")

                null
            }
        }

    /**
     * Creates the account of [username] with [password] via OPAQUE, and stores what this device
     * keeps of it: the user, the vault's root key and, last, the session.
     *
     * In this order:
     * 1. The server hands out a challenge.
     * 2. The device attests over the challenge's bytes, see [AppAttester]. One that fails stops
     *    everything here, before the server has heard of the username.
     * 3. The registration starts, with the username and the attestation, the challenge going back
     *    as it came.
     * 4. Argon2 runs, on this device, and the registration finishes, which creates the account.
     */
    suspend fun register(username: String, password: String): RegistrationOutcome {
        val handshake = Registration()

        try {
            // A `Unit` goes out as no body at all.
            val challengeResult = duckCountClient.post<RegistrationChallengeResponse, Unit>(
                route = DuckCountRoutes.Auth.REGISTRATION_CHALLENGES,
                body = Unit,
            )

            val attestationChallenge = when (challengeResult) {
                is Result.Success -> challengeResult.data.attestationChallenge

                is Result.Error -> {
                    warn("registration challenge failed: ${challengeResult.error}")
                    return challengeResult.error.toRegistrationFailure()
                }
            }

            val attestationObject = when (val attestation = appAttester.attest(Base64.Default.decode(attestationChallenge))) {
                is Attestation.Attested -> attestation.attestationObject

                // Nothing to attest with, which only a server taking no attestation lets through:
                // one running locally with `REGISTRATION_ATTESTATION=off`, as an Android
                // emulator's software chain registers on nothing else either. Any other server
                // refuses it with the `403` of a device it would not create an account on.
                Attestation.Unsupported -> ""

                Attestation.Failed -> return RegistrationOutcome.Failed(AuthenticationError.DEVICE_CHECK_FAILED)
            }

            val startResult = duckCountClient.post<RegistrationStartResponse, RegistrationStartBody>(
                route = DuckCountRoutes.Auth.REGISTRATIONS,
                body = RegistrationStartBody(
                    bytes = Base64.Default.encode(handshake.start(password)),
                    // The server only ever knows the digest of the username, never the username
                    // itself.
                    username = hashLoginUsername(username = username),
                    attestationChallenge = attestationChallenge,
                    attestation = AppAttestationBody(
                        platform = ATTESTATION_PLATFORM,
                        attestationObject = attestationObject,
                    ),
                ),
            )

            // The username is not repeated when finishing: the server kept it, keyed by the
            // session id it just handed out.
            val (sessionId, responseBytes) = when (startResult) {
                is Result.Success -> startResult.data.sessionId to startResult.data.bytes

                is Result.Error -> {
                    warn("registration start failed: ${startResult.error}")
                    return startResult.error.toRegistrationFailure()
                }
            }

            val finished = withContext(Dispatchers.Default) {
                handshake.finish(
                    registrationResponseBytes = Base64.Default.decode(responseBytes),
                    password = password,
                )
            }

            val sealedRootKey = finished.encryptedRootKey()

            val finishResult = duckCountClient.put<TokenResponse, RegistrationFinishBody>(
                route = DuckCountRoutes.Auth.REGISTRATIONS,
                body = RegistrationFinishBody(
                    sessionId = sessionId,
                    messageBytes = Base64.Default.encode(finished.message()),
                    rootKey = EncryptedFieldDto(
                        ciphertext = Base64.Default.encode(sealedRootKey.ciphertext()),
                        nonce = Base64.Default.encode(sealedRootKey.nonce()),
                    ),
                ),
            )

            val tokens = when (finishResult) {
                is Result.Success -> finishResult.data

                is Result.Error -> {
                    warn("registration finish failed: ${finishResult.error}")
                    return finishResult.error.toRegistrationFailure()
                }
            }

            // The server carries the new user's UUID in the short-lived token's `sub`.
            val uuid = tokens.shortLivedToken.jwtSubject() ?: run {
                warn("registration: could not read uuid from short-lived token")
                return RegistrationOutcome.Failed(AuthenticationError.UNKNOWN)
            }

            setAuthenticatedUserUuid(uuid)
            setAuthenticatedUserUsername(username = username)
            setRootKey(finished.rootKey())

            if (!storeTokens(shortLivedToken = tokens.shortLivedToken, longLivedToken = tokens.longLivedToken)) {
                return RegistrationOutcome.Failed(AuthenticationError.UNKNOWN)
            }

            return RegistrationOutcome.Registered(uuid = uuid)
        } catch (e: AuthenticationException) {
            warn("registration OPAQUE error: $e")
            return RegistrationOutcome.Failed(AuthenticationError.CRYPTO_ERROR)
        } catch (e: IllegalArgumentException) {
            // Malformed Base64 in the server's answer.
            warn("registration base64 decode error: $e")
            return RegistrationOutcome.Failed(AuthenticationError.UNKNOWN)
        }
    }

    /**
     * Logs [username] in via OPAQUE, and brings back what the session needs, as Android's login
     * does: the user, the vault's root key, the session, then the profile, the discovery username
     * and new key packages, none of which failing fails the login. Returns null once logged in.
     */
    suspend fun logIn(username: String, password: String): AuthenticationError? {
        val login = Login()

        try {
            val startResult = duckCountClient.post<LoginStartResponse, LoginStartBody>(
                route = DuckCountRoutes.Auth.SESSIONS,
                body = LoginStartBody(
                    credentialRequestBytes = Base64.Default.encode(login.start(password)),
                    // Hashed the same way registration hashed it, otherwise the account simply
                    // cannot be found.
                    username = hashLoginUsername(username = username),
                ),
            )

            // The username is not repeated when finishing: the server kept track of who is
            // logging in, keyed by the session id it just handed out.
            val (sessionId, responseBytes) = when (startResult) {
                is Result.Success -> startResult.data.sessionId to startResult.data.credentialResponseBytes

                is Result.Error -> {
                    warn("login start failed: ${startResult.error}")
                    return startResult.error.toAuthenticationError()
                }
            }

            val finalization = withContext(Dispatchers.Default) {
                login.finish(
                    credentialResponseBytes = Base64.Default.decode(responseBytes),
                    password = password,
                ).message
            }

            val finishResult = duckCountClient.put<LoginFinishResponse, LoginFinishBody>(
                route = DuckCountRoutes.Auth.SESSIONS,
                body = LoginFinishBody(
                    sessionId = sessionId,
                    credentialFinalizationBytes = Base64.Default.encode(finalization),
                ),
            )

            val answer = when (finishResult) {
                is Result.Success -> finishResult.data

                is Result.Error -> {
                    warn("login finish failed: ${finishResult.error}")
                    return finishResult.error.toAuthenticationError()
                }
            }

            // Before anything that lets a sync run. The server keeps no record of who the user's
            // contacts are, so the vault is the only place this device gets them back from, and
            // syncs keep trying until it has.
            setContactsToRestore()

            // Likewise the keys the user's groups handed over, which name the members who are not
            // contacts.
            setMemberProfilesToRestore()

            // A session starts with nothing published, whatever a publication that finished as the
            // last one was ending recorded, see [KeyPackageRepository.publishIfDue].
            clearKeyPackagesPublishedAt()

            // The server carries the user's UUID in the short-lived token's `sub`.
            val uuid = answer.shortLivedToken.jwtSubject()
            uuid?.let(::setAuthenticatedUserUuid) ?: warn("login: could not read uuid from short-lived token")

            setAuthenticatedUserUsername(username = username)

            answer.rootKey?.let { sealedRootKey ->
                setRootKey(
                    login.unsealRootKey(
                        encryptedRootKey = EncryptedField.fromParts(
                            ciphertext = Base64.Default.decode(sealedRootKey.ciphertext),
                            nonce = Base64.Default.decode(sealedRootKey.nonce),
                        ),
                    ),
                )
            } ?: warn("login: the server returned no root key")

            // Before the calls below, which need it to authenticate.
            if (!storeTokens(shortLivedToken = answer.shortLivedToken, longLivedToken = answer.longLivedToken)) {
                return AuthenticationError.UNKNOWN
            }

            uuid?.let {
                profileRepository.restoreProfile(userUuid = it, username = username).onError { error ->
                    warn("login: could not restore the profile: $error")
                }
            }

            discoveryUsernameRepository.syncDiscoveryUsername().onError { error ->
                warn("login: could not restore the discovery username: $error")
            }

            // Replaces the key packages the server holds, whose secrets a previous log-out or
            // install may have taken with it: nobody could open those. A failure leaves them due,
            // and the app publishes them once it is shown, see [publishKeyPackagesIfDue].
            keyPackageRepository.publish().onError { error ->
                warn("login: could not publish the key packages: $error")
            }

            return null
        } catch (e: AuthenticationException.ClientLoginFinalizationFailed) {
            // With OPAQUE, a wrong password (or unknown user) surfaces as the client-side finish
            // step failing, presented as invalid credentials rather than a generic crypto error.
            warn("login finalization failed (likely wrong credentials): $e")
            return AuthenticationError.INVALID_CREDENTIALS
        } catch (e: AuthenticationException) {
            warn("login OPAQUE error: $e")
            return AuthenticationError.CRYPTO_ERROR
        } catch (e: IllegalArgumentException) {
            warn("login base64 decode error: $e")
            return AuthenticationError.UNKNOWN
        }
    }

    /**
     * Stores the profile the user just filled in, on the server first and locally after, then
     * publishes the key packages without which nobody can send the account a contact request.
     * Returns null once both are done, which finishes the registration.
     *
     * The profile lives on the server, so one that could not be sent was not saved and the
     * registration is not finished: the "resume registration" marker is left in place and the
     * user stays on the profile step.
     */
    suspend fun setUpProfile(
        uuid: String,
        username: String,
        fullName: String,
        hasPasswordBeenChecked: Boolean,
    ): AuthenticationError? {
        setHasPasswordBeenChecked(hasPasswordBeenChecked)

        val upload = profileRepository.uploadProfile(userUuid = uuid, username = username, fullName = fullName)

        if (upload is Result.Error) {
            warn("setUpProfile: could not upload the profile: ${upload.error}")

            // The marker stays, so the user comes back to this step rather than to an account with
            // no profile anywhere.
            return upload.error.toAuthenticationError()
        }

        val publication = keyPackageRepository.publish()

        if (publication is Result.Error) {
            warn("setUpProfile: could not publish the key packages: ${publication.error}")

            // The marker stays here too, and coming back to this step publishes again.
            return publication.error.toAuthenticationError()
        }

        // Account set up, drop the saved "resume registration" marker.
        clearPendingRegistration()

        return null
    }

    /** Remembers a registration whose profile step still needs completing, across launches. */
    fun markPendingRegistration(uuid: String, username: String, hasPasswordBeenChecked: Boolean) {
        setPendingRegistration(
            PendingRegistration(uuid = uuid, username = username, hasPasswordBeenChecked = hasPasswordBeenChecked),
        )
    }

    /** The registration awaiting its profile, if any, for a launch to resume it. */
    suspend fun pendingRegistration(): PendingRegistration? = authenticationUtils.getPendingRegistration()

    /**
     * True if a session is stored on this device, a long-lived token being there, which is decided
     * with no network call, so that it stays right offline whatever the short-lived token's expiry.
     */
    suspend fun isLoggedIn(): Boolean = getLongLivedToken()?.isNotBlank() ?: false

    /**
     * Publishes the user's key packages when they are due for replacing, or the login that should
     * have put them up could not, see [KeyPackageRepository.publishIfDue]. For the app to call
     * whenever its main screen is shown, as Android's dashboard does.
     */
    suspend fun publishKeyPackagesIfDue() {
        keyPackageRepository.publishIfDue().onError { error ->
            warn("could not publish the key packages: $error")
        }
    }

    /**
     * Stores the session's tokens, and says whether it could.
     *
     * Last of what a login or a registration stores: the long-lived token is what marks a session,
     * see [isLoggedIn], and stored ahead of the rest, a failure or the process ending in between
     * would leave the next launch a session without the user it belongs to. A session whose tokens
     * this device did not keep is not one either, so a failure clears them.
     */
    private suspend fun storeTokens(shortLivedToken: String, longLivedToken: String): Boolean =
        try {
            setTokens(shortLivedToken = shortLivedToken, longLivedToken = longLivedToken)

            true
        } catch (exception: CancellationException) {
            // The caller going away, which the catch below would otherwise swallow.
            throw exception
        } catch (exception: Exception) {
            warn("could not store the session's tokens: $exception")

            clearTokens()

            false
        }

    private fun NetworkError.toAuthenticationError(): AuthenticationError =
        toAuthenticationError(isOnline = networkConnectivity.isOnline())

    private fun NetworkError.toRegistrationFailure(): RegistrationOutcome.Failed =
        RegistrationOutcome.Failed(toRegistrationError(isOnline = networkConnectivity.isOnline()))

    /**
     * Maps a failed profile upload to an [AuthenticationError]. The vault half is folded in: to
     * somebody finishing a registration, "the key could not be written" and "the profile could not
     * be sent" are the same unfinished step.
     */
    private fun ProfileError.toAuthenticationError(): AuthenticationError = when (this) {
        is ProfileError.Network -> error.toAuthenticationError()

        is ProfileError.Vault -> error.toAuthenticationError()

        ProfileError.FullNameTooLong -> AuthenticationError.FULL_NAME_TOO_LONG

        // Neither can happen on the way out: NotStored is a read outcome, and a field this device
        // just sealed is one it can seal.
        ProfileError.NotStored,
        ProfileError.Crypto,
            -> AuthenticationError.UNKNOWN
    }

    /** Maps a failed key package publication to an [AuthenticationError], as for the profile. */
    private fun KeyPackageError.toAuthenticationError(): AuthenticationError = when (this) {
        is KeyPackageError.Network -> error.toAuthenticationError()

        is KeyPackageError.Identity -> when (val identityError = error) {
            is MlsIdentityError.Vault -> identityError.error.toAuthenticationError()

            // The session was set up moments ago by the registration that led here, so its absence
            // is not something the user can be asked to fix.
            MlsIdentityError.MissingSession -> AuthenticationError.CRYPTO_ERROR

            MlsIdentityError.Crypto -> AuthenticationError.CRYPTO_ERROR
        }

        KeyPackageError.Crypto -> AuthenticationError.CRYPTO_ERROR
    }

    private fun VaultError.toAuthenticationError(): AuthenticationError = when (this) {
        is VaultError.Network -> error.toAuthenticationError()

        // The root key was set moments ago by the registration that led here, so its absence is
        // not something the user can be asked to fix.
        VaultError.MissingRootKey,
        VaultError.Crypto,
        VaultError.MalformedKeyRing,
        VaultError.MalformedContactBook,
        VaultError.MalformedMemberProfileBook,
        VaultError.MalformedBlockList,
        VaultError.OrphanedEntry,
        VaultError.Contended,
            -> AuthenticationError.CRYPTO_ERROR
    }

    private fun warn(message: String) = println("$TAG: $message")
}

/** What [AccountAuthentication.register] came to. */
sealed interface RegistrationOutcome {

    /** The account exists and this device holds its session, the user's UUID being [uuid]. */
    data class Registered(val uuid: String) : RegistrationOutcome

    data class Failed(val error: AuthenticationError) : RegistrationOutcome
}
