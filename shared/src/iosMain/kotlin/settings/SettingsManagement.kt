package settings

import account.AccountDeletion
import account.PasswordChange
import app.lidory.duckcount.ExternalLinks
import app.lidory.duckcount.PasswordRequirements
import app.lidory.duckcount.ProfileLimits
import app.lidory.duckcount.usernameMaximumLength
import authentication.NetworkConnectivity
import authenticationUtils.getAuthenticatedUserUsername
import authenticationUtils.getAuthenticatedUserUuid
import authenticationUtils.getDiscoveryUsernameFlow
import contact.UsernameInputFailure
import contact.toInputFailure
import database.data.repository.UserRepository
import discoveryUsername.DiscoveryUsernameRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import networking.DuckCountClient
import profile.ProfileRepository
import util.Result
import util.onError

private const val TAG = "SettingsManagement"

/**
 * What the iOS settings screens run, with the same repositories and in the same order as Android's
 * `SettingsCallBacksImpl`, which lives in the Android app, their failures told as it tells them,
 * see [AccountDeletionFailure].
 *
 * Unlike Android's, ending the session restarts nothing here: iOS cannot start the app over in a
 * process of its own, so the app does it within this one once [logOut] or [deleteAccount] returns,
 * see `di.restartSession`. Nor is there a push subscription to tear down: iOS receives no push yet.
 */
class SettingsManagement(
    private val duckCountClient: DuckCountClient,
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val discoveryUsernameRepository: DiscoveryUsernameRepository,
    private val accountDeletion: AccountDeletion,
    private val passwordChange: PasswordChange,
    private val networkConnectivity: NetworkConnectivity,
) {

    /** Longest username the library accepts, for the "too long" message to count against. */
    val maxUsernameLength: Int = usernameMaximumLength()

    /** Longest full name the server takes on a profile, which the profile's form stops at. */
    val maxFullNameLength: Int = ProfileLimits.MAX_FULL_NAME_LENGTH

    /** Shortest password the forms take. */
    val minimumPasswordLength: Int = PasswordRequirements.MINIMUM_PASSWORD_STRENGTH

    val learnMoreLink: String = ExternalLinks.LIDORY_WEBSITE
    val sourceCodeLink: String = ExternalLinks.SOURCE_CODE
    val termsOfUseLink: String = ExternalLinks.TERMS_OF_USE
    val privacyPolicyLink: String = ExternalLinks.PRIVACY_POLICY

    fun authenticatedUserUuid(): String? = getAuthenticatedUserUuid()

    /** The username the user logs in with. */
    fun authenticatedUsername(): String? = getAuthenticatedUserUsername()

    /**
     * Ends the session on this device, see [DuckCountClient.endSession], for the app to start over.
     * Seen through whatever becomes of the screen that asked, which a log-out the user believes done
     * must not be left halfway through.
     */
    suspend fun logOut() {
        withContext(NonCancellable) {
            duckCountClient.endSession()
        }
    }

    /**
     * Deletes the user's account once [password] proves it is theirs, telling their contacts first,
     * see [AccountDeletion.delete], which ends the session too. Null once deleted, the app having to
     * start over then, and also when the account turns out to be gone already, an earlier attempt
     * having deleted it without its answer coming back.
     *
     * Not cancellable, for the reasons [logOut] gives: a deletion that went through must end in the
     * session's end.
     */
    suspend fun deleteAccount(password: String): AccountDeletionFailure? = withContext(NonCancellable) {
        val userUuid = getAuthenticatedUserUuid() ?: return@withContext AccountDeletionFailure.UNKNOWN

        when (val deletion = accountDeletion.delete(userUuid = userUuid, password = password)) {
            is Result.Success -> null

            is Result.Error -> {
                warn("deleteAccount: could not delete the account: ${deletion.error}")
                deletion.error.toFailure(isOnline = networkConnectivity.isOnline())
            }
        }
    }

    /**
     * Changes the user's password from [currentPassword] to [newPassword], once [newPassword] turns
     * up in no known data breach, or the check could not be run, see [PasswordChange.change], which
     * sees it through once it may be sent. Null once changed: the user's keys, their data and the
     * session on this device stay as they are.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeFailure? {
        val userUuid = getAuthenticatedUserUuid() ?: return PasswordChangeFailure.UNKNOWN
        val username = getAuthenticatedUserUsername() ?: return PasswordChangeFailure.UNKNOWN

        val change = passwordChange.change(
            userUuid = userUuid,
            username = username,
            currentPassword = currentPassword,
            newPassword = newPassword,
        )

        return when (change) {
            is Result.Success -> null

            is Result.Error -> {
                warn("changePassword: could not change the password: ${change.error}")
                change.error.toFailure(isOnline = networkConnectivity.isOnline())
            }
        }
    }

    /** The full name on the profile of [userUuid], as this device holds it. */
    fun fullName(userUuid: String): Flow<String?> = userRepository.getFullName(uuid = userUuid)

    /**
     * Pulls the profile of [userUuid] from the server into this device's copy. A failure is not
     * reported: the copy already held is what the screen shows either way.
     */
    suspend fun refreshProfile(userUuid: String) {
        profileRepository.restoreProfile(
            userUuid = userUuid,
            username = getAuthenticatedUserUsername().orEmpty(),
        ).onError { error ->
            warn("refreshProfile: could not refresh the profile: $error")
        }
    }

    /**
     * Saves the profile of [userUuid], server first, with [fullName] taken without the whitespace
     * around it, see [ProfileRepository.uploadProfile]. Null once saved: nothing is kept on this
     * device when the server did not take it.
     */
    suspend fun updateProfile(userUuid: String, fullName: String): ProfileSaveFailure? {
        val upload = profileRepository.uploadProfile(
            userUuid = userUuid,
            username = getAuthenticatedUserUsername().orEmpty(),
            fullName = fullName,
        )

        return when (upload) {
            is Result.Success -> null

            is Result.Error -> {
                warn("updateProfile: could not save the profile: ${upload.error}")
                upload.error.toSaveFailure(isOnline = networkConnectivity.isOnline())
            }
        }
    }

    /** The username the user reserved to be discoverable by, or null while they have not reserved one. */
    fun reservedUsername(): Flow<String?> = getDiscoveryUsernameFlow()

    /**
     * Brings this device's copy of the reserved username in line with the vault, which says which
     * one the account holds. A failure is not reported: the user keeps seeing the local copy.
     */
    suspend fun refreshReservedUsername() {
        discoveryUsernameRepository.syncDiscoveryUsername().onError { error ->
            warn("refreshReservedUsername: could not refresh the username: $error")
        }
    }

    /** Why [username] cannot be reserved as typed, or null when it is valid, or empty. */
    fun validateUsername(username: String): UsernameInputFailure? =
        app.lidory.duckcount.validateUsername(username = username)?.toInputFailure()

    /** [username] as the server knows it by, which a reserved one is compared by. */
    fun normalizeUsername(username: String): String = app.lidory.duckcount.normalizeUsername(username = username)

    /** Reserves [username], replacing whatever the user reserved before. Null once reserved. */
    suspend fun reserveUsername(username: String): ReserveUsernameFailure? {
        val userUuid = getAuthenticatedUserUuid() ?: return ReserveUsernameFailure.UNAUTHORIZED

        return when (val reservation = discoveryUsernameRepository.reserve(userUuid = userUuid, username = username)) {
            is Result.Success -> null

            is Result.Error -> {
                warn("reserveUsername: could not reserve the username: ${reservation.error}")
                reservation.error.toReserveFailure(isOnline = networkConnectivity.isOnline())
            }
        }
    }

    private fun warn(message: String) = println("$TAG: $message")
}
