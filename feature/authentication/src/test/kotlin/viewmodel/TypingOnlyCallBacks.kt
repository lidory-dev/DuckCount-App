package viewmodel

import AuthOutcome
import AuthenticationCallBacks
import PendingProfile
import UsernameInputError

/**
 * Answers only what a form asks while it is typed in, every username passing the rules but one
 * holding a capital or whitespace around it, which the library refuses as an invalid character.
 */
class TypingOnlyCallBacks : AuthenticationCallBacks {
    override val passwordStrength = 15
    override val maxUsernameLength = 32
    override val maxFullNameLength = 64
    override val termsOfUseLink = "https://example.com/terms"
    override val privacyPolicyLink = "https://example.com/privacy"

    override suspend fun passwordLeakAmount(password: String): AuthOutcome<Int> = TODO()
    override suspend fun registration(username: String, password: String): AuthOutcome<String> = TODO()
    override suspend fun login(username: String, password: String): AuthOutcome<Unit> = TODO()
    override fun validateUsername(username: String): UsernameInputError? =
        if (username != username.lowercase() || username != username.trim()) {
            UsernameInputError.INVALID_CHARACTER
        } else {
            null
        }

    override suspend fun insertUser(
        uuid: String,
        username: String,
        fullName: String,
        hasPasswordBeenChecked: Boolean,
    ): AuthOutcome<Unit> = TODO()

    override suspend fun markPendingRegistration(
        uuid: String,
        username: String,
        hasPasswordBeenChecked: Boolean,
    ): Unit = TODO()

    override suspend fun getPendingRegistration(): PendingProfile? = TODO()
    override suspend fun getShortLivedToken(): String? = TODO()
    override suspend fun getLongLivedToken(): String? = TODO()
    override suspend fun isLoggedIn(): Boolean = TODO()
}
