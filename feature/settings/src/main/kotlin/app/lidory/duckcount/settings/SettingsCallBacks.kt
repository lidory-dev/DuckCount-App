package app.lidory.duckcount.settings

import kotlinx.coroutines.flow.Flow

interface SettingsCallBacks {
    val learnMoreLink: String

    val appVersionName: String

    val licences: List<String>

    fun logOut()

    fun getAuthenticatedUserUuid(): String?

    fun getUserFullName(uuid: String): Flow<String?>

    suspend fun updateFullName(userUuid: String, fullName: String)
}
