package app.lidory.duckcount.settings

import app.lidory.duckcount.settings.model.AccountDeletionError
import app.lidory.duckcount.settings.model.PasswordChangeError
import app.lidory.duckcount.settings.model.ProfileSaveError
import app.lidory.duckcount.settings.model.ReserveDiscoveryUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import kotlinx.coroutines.flow.Flow

interface SettingsCallBacks {

    /** Longest username `lib-duckcount` accepts, for the "too long" message to count against. */
    val maxUsernameLength: Int

    /** Longest full name the server takes on a profile, which the profile's form stops at. */
    val maxFullNameLength: Int

    val minimumPasswordStrength: Int

    val learnMoreLink: String

    /** The privacy policy the settings list opens in the browser. */
    val privacyPolicyLink: String

    val appVersionName: String

    val licences: List<String>

    /**
     * Ends the session, empties the local database and restarts the app, whatever becomes of
     * the screen that asked. Does not return.
     */
    suspend fun logOut()

    /**
     * Deletes the user's account once [password] proves it is theirs, telling their contacts
     * first, then ends the session and restarts the app like [logOut], whatever becomes of the
     * screen that asked. Does not return then, nor when the account turns out to be gone already,
     * an earlier attempt having deleted it without its answer coming back.
     *
     * Returns why it failed otherwise. The session is still there then, unless the server turned it
     * down meanwhile, which ends it and restarts the app as any expiry does, and so is the account
     * as far as the attempt could tell, but for [AccountDeletionError.UNCONFIRMED].
     */
    suspend fun deleteAccount(password: String): AccountDeletionError

    /**
     * Changes the user's password from [currentPassword] to [newPassword], once [newPassword]
     * turns up in no known data breach, or the check could not be run, and returns why it failed,
     * or null on success. Nothing else changes: the user's keys, their data and the session on
     * this device stay as they are.
     *
     * Seen through once the change may be sent, whatever becomes of the screen that asked. The
     * session is still there after a failure, unless the server turned it down meanwhile, which
     * ends it and restarts the app as any expiry does, and so is the password, but for
     * [PasswordChangeError.UNCONFIRMED].
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeError?

    fun getAuthenticatedUserUuid(): String?

    fun getUserUsername(): String?

    fun getUserFullName(uuid: String): Flow<String?>

    /**
     * Pulls the profile from the server into the local cache.
     *
     * Failing is not reported, the cached copy is what the screen shows either way.
     */
    suspend fun refreshUserProfile(userUuid: String)

    /**
     * Updates the user's profile, server first, with [fullName] taken without the whitespace
     * around it.
     *
     * Nothing is kept locally when this fails, the profile lives on the server, so a
     * save that did not reach it did not happen.
     */
    suspend fun updateUserProfile(userUuid: String, fullName: String): ProfileSaveError?

    /**
     * Emits the username the user reserved to be discoverable by, or null when they
     * have not reserved one yet.
     */
    fun getReservedUsername(): Flow<String?>

    /**
     * Pulls the reserved discovery username from the vault into the local copy.
     *
     * Failing is not reported, the screen shows the copy it already had, which is what
     * it is for.
     */
    suspend fun refreshReservedDiscoveryUsername()

    /**
     * Returns why [username] cannot be reserved as typed, or null when it is valid, or empty:
     * nothing typed yet isn't a mistake worth pointing out, the button stays disabled anyway.
     */
    fun validateUsername(username: String): UsernameInputError?

    /** Returns the form [username] is reserved under, so it can be compared to a draft. */
    fun normalizeUsername(username: String): String

    /**
     * Reserves [username], replacing any previous reservation, and returns why it
     * failed, or null on success.
     */
    suspend fun reserveDiscoveryUsername(username: String): ReserveDiscoveryUsernameError?
}
