package app.lidory.duckcount.settings

import android.util.Log
import app.lidory.duckcount.BuildConfig
import app.lidory.duckcount.ExternalLinks.LIDORY_WEBSITE
import app.lidory.duckcount.LICENCES_LIST
import app.lidory.duckcount.PasswordRequirements
import app.lidory.duckcount.networking.NetworkConnectivity
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
import util.Result
import util.onError
import app.lidory.duckcount.normalizeUsername as normalizeUsernameShared
import app.lidory.duckcount.validateUsername as validateUsernameShared

private const val TAG = "SettingsCallBacks"

class SettingsCallBacksImpl(
    private val userRepository: UserRepository,
    private val duckCountClient: DuckCountClient,
    private val networkConnectivity: NetworkConnectivity,
    private val profileRepository: ProfileRepository,
    private val discoveryUsernameRepository: DiscoveryUsernameRepository,
) : SettingsCallBacks {

    override val maxUsernameLength: Int = usernameMaximumLength()

    override val minimumPasswordStrength: Int = PasswordRequirements.MINIMUM_PASSWORD_STRENGTH

    override val learnMoreLink: String = LIDORY_WEBSITE

    override val appVersionName: String = BuildConfig.VERSION_NAME

    override val licences: List<String> = LICENCES_LIST

    override suspend fun logOut() {
        duckCountClient.endSession()
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
