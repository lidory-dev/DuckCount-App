package app.lidory.duckcount.settings

import app.lidory.duckcount.settings.model.ReserveUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import kotlinx.coroutines.flow.Flow

interface SettingsCallBacks {

    /** Longest username `lib-duckcount` accepts, for the "too long" message to count against. */
    val maxUsernameLength: Int

    val minimumPasswordStrength: Int

    val learnMoreLink: String

    val appVersionName: String

    val licences: List<String>

    fun logOut()

    fun getAuthenticatedUserUuid(): String?

    fun getUserUsername(): String?

    fun getUserFullName(uuid: String): Flow<String?>

    suspend fun updateUserFullName(userUuid: String, fullName: String)

    /**
     * Emits the username the user reserved to be discoverable by, or null when they
     * have not reserved one yet.
     */
    fun getReservedUsername(): Flow<String?>

    /** Returns why [username] cannot be reserved as typed, or null when it is valid. */
    fun validateUsername(username: String): UsernameInputError?

    /** Returns the form [username] is reserved under, so it can be compared to a draft. */
    fun normalizeUsername(username: String): String

    /**
     * Reserves [username], replacing any previous reservation, and returns why it
     * failed, or null on success.
     */
    suspend fun reserveUsername(username: String): ReserveUsernameError?
}
