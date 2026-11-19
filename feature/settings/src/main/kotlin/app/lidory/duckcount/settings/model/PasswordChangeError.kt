package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/**
 * Why changing the password failed: the new password leaked, which a screen of its own says, as at
 * registration, or [Form], what the form says. The composeApp callback impl maps the low-level
 * error onto this.
 */
sealed interface PasswordChangeFailure {

    /** The new password turns up in known data breaches, [count] times, so nothing was sent. */
    data class Leaked(val count: Int) : PasswordChangeFailure

    /** Any other failure, which the form shows, see [PasswordChangeError]. */
    data class Form(val error: PasswordChangeError) : PasswordChangeFailure
}

/**
 * Why changing the password failed, as the form says it: any reason but a leaked new password,
 * see [PasswordChangeFailure].
 *
 * The password is still the one it was in every case but [UNCONFIRMED], and so is the session,
 * unless the server turned it down meanwhile, which ends it and restarts the app as any expiry
 * does.
 */
enum class PasswordChangeError {
    /** The current password typed is not the account's. Shown on its field. */
    WRONG_PASSWORD,

    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** Anything else (server error, unexpected response, …). */
    UNKNOWN,

    /**
     * The change went out but its answer never came back, so the password may have changed
     * already. If it did, the session ends at the next refresh, and the user logs in again with
     * the new password. Trying again before then finds the current password typed refused, as
     * [WRONG_PASSWORD], which the message warns of too: the new password is the account's.
     */
    UNCONFIRMED,
    ;

    /** Whether this is shown on a field of the form, rather than in a snackbar. */
    val isFieldError: Boolean
        get() = this == WRONG_PASSWORD
}

@StringRes
fun PasswordChangeError.messageRes(): Int = when (this) {
    PasswordChangeError.WRONG_PASSWORD -> R.string.password_change_error_wrong_password
    PasswordChangeError.NO_INTERNET -> R.string.password_change_error_no_internet
    PasswordChangeError.TIMEOUT -> R.string.password_change_error_timeout
    PasswordChangeError.SERVER_UNREACHABLE -> R.string.password_change_error_server_unreachable
    PasswordChangeError.UNKNOWN -> R.string.password_change_error_unknown
    PasswordChangeError.UNCONFIRMED -> R.string.password_change_error_unconfirmed
}
