package app.lidory.duckcount.settings

import app.lidory.duckcount.BuildConfig
import app.lidory.duckcount.ExternalLinks.LIDORY_WEBSITE
import app.lidory.duckcount.LICENCES_LIST
import app.lidory.duckcount.PasswordRequirements
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.settings.model.ReserveUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import app.lidory.duckcount.usernameMaximumLength
import authenticationUtils.clearAuthenticatedUserUsername
import authenticationUtils.clearAuthenticatedUserUuid
import authenticationUtils.clearDiscoveryUsername
import authenticationUtils.clearRootKey
import authenticationUtils.clearTokens
import authenticationUtils.getAuthenticatedUserUsername
import authenticationUtils.getDiscoveryUsernameFlow
import authenticationUtils.setDiscoveryUsername
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import networking.DuckCountClient
import util.Result
import app.lidory.duckcount.normalizeUsername as normalizeUsernameShared
import app.lidory.duckcount.validateUsername as validateUsernameShared

class SettingsCallBacksImpl(
    private val userRepository: UserRepository,
    private val duckCountClient: DuckCountClient,
    private val networkConnectivity: NetworkConnectivity,
) : SettingsCallBacks {

    override val maxUsernameLength: Int = usernameMaximumLength()

    override val minimumPasswordStrength: Int = PasswordRequirements.MINIMUM_PASSWORD_STRENGTH

    override val learnMoreLink: String = LIDORY_WEBSITE

    override val appVersionName: String = BuildConfig.VERSION_NAME

    override val licences: List<String> = LICENCES_LIST

    override fun logOut() {
        clearTokens()
        clearAuthenticatedUserUuid()
        clearAuthenticatedUserUsername()
        clearDiscoveryUsername()
        clearRootKey()

        // The stored tokens are only read once per client, so they have to be dropped
        // from the client too: otherwise the next user to log in keeps sending this
        // session's token until it expires.
        duckCountClient.clearAuthenticationCache()
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

    override suspend fun updateUserFullName(userUuid: String, fullName: String) {
        userRepository.updateFullName(userUuid, fullName)
    }

    override fun getReservedUsername(): Flow<String?> {
        return getDiscoveryUsernameFlow()
    }

    override fun validateUsername(username: String): UsernameInputError? {
        return validateUsernameShared(username = username)?.toUsernameInputError()
    }

    override fun normalizeUsername(username: String): String {
        return normalizeUsernameShared(username = username)
    }

    override suspend fun reserveUsername(username: String): ReserveUsernameError? {
        val userUuid = getAuthenticatedUserUuid() ?: return ReserveUsernameError.UNAUTHORIZED

        return when (
            val result = userRepository.reserveUsername(userUuid = userUuid, username = username)
        ) {
            is Result.Success -> {
                // The server holds nothing but the digest, so the handle itself is kept
                // here: it is the only record that this user is discoverable.
                setDiscoveryUsername(username = normalizeUsername(username = username))

                null
            }

            is Result.Error -> result.error.toReserveUsernameError(
                isOnline = networkConnectivity.isOnline(),
            )
        }
    }
}
