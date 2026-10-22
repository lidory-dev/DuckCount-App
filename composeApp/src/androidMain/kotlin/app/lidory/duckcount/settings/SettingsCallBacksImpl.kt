package app.lidory.duckcount.settings

import account.AccountDeletion
import android.content.Context
import android.util.Log
import app.lidory.duckcount.BuildConfig
import app.lidory.duckcount.ExternalLinks.LIDORY_WEBSITE
import app.lidory.duckcount.LICENCES_LIST
import app.lidory.duckcount.PasswordRequirements
import app.lidory.duckcount.RestartNotice
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.settings.model.AccountDeletionError
import app.lidory.duckcount.settings.model.ProfileSaveError
import app.lidory.duckcount.settings.model.ReserveDiscoveryUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import app.lidory.duckcount.usernameMaximumLength
import authenticationUtils.getAuthenticatedUserUsername
import authenticationUtils.getDiscoveryUsernameFlow
import database.data.repository.UserRepository
import discoveryUsername.DiscoveryUsernameRepository
import kotlinx.coroutines.flow.Flow
import networking.DuckCountClient
import profile.ProfileRepository
import push.PushRegistrar
import util.Result
import util.onError
import app.lidory.duckcount.restartApp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.lidory.duckcount.normalizeUsername as normalizeUsernameShared
import app.lidory.duckcount.validateUsername as validateUsernameShared

private const val TAG = "SettingsCallBacks"

class SettingsCallBacksImpl(
    private val context: Context,
    private val userRepository: UserRepository,
    private val duckCountClient: DuckCountClient,
    private val networkConnectivity: NetworkConnectivity,
    private val profileRepository: ProfileRepository,
    private val discoveryUsernameRepository: DiscoveryUsernameRepository,
    private val pushRegistrar: PushRegistrar,
    private val accountDeletion: AccountDeletion,
) : SettingsCallBacks {

    override val maxUsernameLength: Int = usernameMaximumLength()

    override val minimumPasswordStrength: Int = PasswordRequirements.MINIMUM_PASSWORD_STRENGTH

    override val learnMoreLink: String = LIDORY_WEBSITE

    override val appVersionName: String = BuildConfig.VERSION_NAME

    override val licences: List<String> = LICENCES_LIST

    /**
     * Not cancellable, restart included: the screen that asked can be left while the server
     * call below is still running, and a log-out it took with it would leave the session and
     * its keys on the device, with the user believing they were gone. The restart goes through
     * the application for the same reason, since the screen is no longer there to do it.
     */
    override suspend fun logOut() {
        withContext(NonCancellable) {
            // First, and not inside `endSession`: telling the server to stop pushing to this
            // device is a call that needs the tokens `endSession` is about to drop.
            pushRegistrar.tearDown()

            duckCountClient.endSession()

            // The session has ended, so the next one needs a process of its own, which
            // starts on the onboarding screen.
            context.restartApp()
        }
    }

    /**
     * Not cancellable, restart included, for the reasons [logOut] gives: the screen that asked
     * can be left while the contacts are being told or the account deleted, and a deletion that
     * went through must still end in a restart.
     *
     * There is no push tear-down here, unlike [logOut]: the server dropped this device's push
     * subscription along with the account, and the distributor's side goes once the app is up
     * again with nobody signed in, see [app.lidory.duckcount.push.PushTearDown].
     */
    override suspend fun deleteAccount(password: String): AccountDeletionError =
        withContext(NonCancellable) {
            val userUuid = getAuthenticatedUserUuid() ?: return@withContext AccountDeletionError.UNKNOWN

            when (val deletion = accountDeletion.delete(userUuid = userUuid, password = password)) {
                // The session ended with the account, and the next one needs a process of its
                // own, as after a log-out.
                is Result.Success -> context.restartApp(notice = RestartNotice.ACCOUNT_DELETED)

                is Result.Error -> {
                    Log.w(TAG, "deleteAccount: could not delete the account: ${deletion.error}")

                    deletion.error.toAccountDeletionError(isOnline = networkConnectivity.isOnline())
                }
            }
        }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override fun getUserUsername(): String? {
        return getAuthenticatedUserUsername()
    }

    override fun getUserFullName(uuid: String): Flow<String?> {
        return userRepository.getFullName(uuid)
    }

    override suspend fun refreshUserProfile(userUuid: String) {
        profileRepository.restoreProfile(
            userUuid = userUuid,
            username = getAuthenticatedUserUsername().orEmpty(),
        ).onError { error ->
            // Nothing to tell the user: they keep seeing the cached copy, which is what
            // it is for.
            Log.w(TAG, "refreshUserProfile: could not refresh the profile: $error")
        }
    }

    override suspend fun updateUserProfile(userUuid: String, fullName: String): ProfileSaveError? {
        val upload = profileRepository.uploadProfile(
            userUuid = userUuid,
            username = getAuthenticatedUserUsername().orEmpty(),
            fullName = fullName,
        )

        return when (upload) {
            is Result.Success -> null

            is Result.Error -> upload.error.toProfileSaveError(
                isOnline = networkConnectivity.isOnline(),
            )
        }
    }

    override fun getReservedUsername(): Flow<String?> {
        return getDiscoveryUsernameFlow()
    }

    override suspend fun refreshReservedDiscoveryUsername() {
        discoveryUsernameRepository.syncDiscoveryUsername().onError { error ->
            // Nothing to tell the user: they keep seeing the local copy, which is what
            // it is for.
            Log.w(TAG, "refreshReservedUsername: could not refresh the username: $error")
        }
    }

    override fun validateUsername(username: String): UsernameInputError? {
        return validateUsernameShared(username = username)?.toUsernameInputError()
    }

    override fun normalizeUsername(username: String): String {
        return normalizeUsernameShared(username = username)
    }

    override suspend fun reserveDiscoveryUsername(username: String): ReserveDiscoveryUsernameError? {
        val userUuid = getAuthenticatedUserUuid() ?: return ReserveDiscoveryUsernameError.UNAUTHORIZED

        val reservation = discoveryUsernameRepository.reserve(
            userUuid = userUuid,
            username = username,
        )

        return when (reservation) {
            is Result.Success -> null

            is Result.Error -> reservation.error.toReserveUsernameError(
                isOnline = networkConnectivity.isOnline(),
            )
        }
    }
}
