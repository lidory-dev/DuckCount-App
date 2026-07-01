package app.lidory.duckcount.settings

import app.lidory.duckcount.BuildConfig
import app.lidory.duckcount.ExternalLinks.LIDORY_WEBSITE
import app.lidory.duckcount.LICENCES_LIST
import authenticationUtils.clearAuthenticatedUserUuid
import authenticationUtils.clearTokens
import database.user.UserRepository
import kotlinx.coroutines.flow.Flow

class SettingsCallBacksImpl(
    private val userRepository: UserRepository,
) : SettingsCallBacks {

    override val learnMoreLink: String = LIDORY_WEBSITE

    override val appVersionName: String = BuildConfig.VERSION_NAME

    override val licences: List<String> = LICENCES_LIST

    override fun logOut() {
        clearTokens()

        clearAuthenticatedUserUuid()
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override fun getUserFullName(uuid: String): Flow<String?> {
        return userRepository.getFullName(uuid)
    }

    override suspend fun updateFullName(userUuid: String, fullName: String) {
        userRepository.updateFullName(userUuid, fullName)
    }
}
