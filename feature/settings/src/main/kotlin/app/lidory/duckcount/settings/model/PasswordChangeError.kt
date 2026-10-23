package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/**
 * Why changing the password failed, at a granularity the UI can explain.
 * The composeApp callback impl maps the low-level error onto this.
 *
 * The password is still the one it was in every case but [UNCONFIRMED], and so is the session,
 * unless the server turned it down meanwhile, which ends it and restarts the app as any expiry
 * does.
 */
enum class PasswordChangeError {
    /** The current password typed is not the account's. Shown on its field. */
    WRONG_PASSWORD,

    /**
     * The new password turns up in a known data breach, so nothing was sent. Shown on its field.
     */
    LEAKED,

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
        get() = this == WRONG_PASSWORD || this == LEAKED
}

@StringRes
fun PasswordChangeError.messageRes(): Int = when (this) {
    PasswordChangeError.WRONG_PASSWORD -> R.string.password_change_error_wrong_password
    PasswordChangeError.LEAKED -> R.string.password_change_error_leaked
    PasswordChangeError.NO_INTERNET -> R.string.password_change_error_no_internet
    PasswordChangeError.TIMEOUT -> R.string.password_change_error_timeout
    PasswordChangeError.SERVER_UNREACHABLE -> R.string.password_change_error_server_unreachable
    PasswordChangeError.UNKNOWN -> R.string.password_change_error_unknown
    PasswordChangeError.UNCONFIRMED -> R.string.password_change_error_unconfirmed
}
