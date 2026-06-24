package app.lidory.duckcount.settings

import kotlinx.coroutines.flow.Flow

interface SettingsCallBacks {
    fun logOut()

    fun getAuthenticatedUserUuid(): String?

    fun getUserFullName(uuid: String): Flow<String?>

    suspend fun updateFullName(userUuid: String, fullName: String)

    val licences: List<String>
}
