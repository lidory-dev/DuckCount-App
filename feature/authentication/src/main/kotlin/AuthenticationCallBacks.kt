interface AuthenticationCallBacks {

    val passwordStrength: Int

    /** Longest username `lib-duckcount` accepts, for the "too long" message to count against. */
    val maxUsernameLength: Int

    /** Number of times the password appears in known breaches (Have I Been Pwned). */
    suspend fun passwordLeakAmount(password: String): AuthOutcome<Int>

    /** Registers the user via OPAQUE. On success, returns the newly-created user's UUID. */
    suspend fun registration(username: String, password: String): AuthOutcome<String>

    /** Authenticates the user via OPAQUE. */
    suspend fun login(username: String, password: String): AuthOutcome<Unit>

    /** Returns why [username] cannot be used as typed, or null when it is valid. */
    fun validateUsername(username: String): UsernameInputError?

    /**
     * Stores the profile the user just filled in, on the server first and locally after.
     *
     * The profile lives on the server, so one that could not be sent was not saved and
     * the registration is not finished, the "resume registration" marker is left in
     * place and the user stays on the profile step.
     */
    suspend fun insertUser(
        uuid: String,
        username: String,
        fullName: String,
        hasPasswordBeenChecked: Boolean,
    ): AuthOutcome<Unit>

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
