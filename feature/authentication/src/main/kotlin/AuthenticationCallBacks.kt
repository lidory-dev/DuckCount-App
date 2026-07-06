interface AuthenticationCallBacks {

    val passwordStrength: Int

    /** Number of times the password appears in known breaches (Have I Been Pwned). */
    suspend fun passwordLeakAmount(password: String): AuthOutcome<Int>

    /** Registers the user via OPAQUE. On success, returns the newly-created user's UUID. */
    suspend fun registration(username: String, password: String): AuthOutcome<String>

    /** Authenticates the user via OPAQUE. */
    suspend fun login(username: String, password: String): AuthOutcome<Unit>

    suspend fun insertUser(uuid: String, username: String, fullName: String, hasPasswordBeenChecked: Boolean)

    /** Remembers a registration whose local profile step still needs completing (survives restart). */
    suspend fun markPendingRegistration(uuid: String, username: String, hasPasswordBeenChecked: Boolean)

    /** The registration awaiting profile completion, if any, used to resume after a restart. */
    suspend fun getPendingRegistration(): PendingProfile?

    suspend fun getShortLivedToken(): String?

    suspend fun getLongLivedToken(): String?

    /**
     * True if a session is stored locally (a refresh token is present). Decided locally with no
     * network call, so it stays correct offline and regardless of the short-lived token's expiry.
     */
    suspend fun isLoggedIn(): Boolean
}
