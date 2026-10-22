package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/**
 * Why deleting the account failed, at a granularity the UI can explain.
 * The composeApp callback impl maps the low-level error onto this.
 *
 * The session is still there in every case, unless the server turned it down meanwhile, which
 * ends it and restarts the app as any expiry does. So is the account, as far as the attempt could
 * tell, but for [UNCONFIRMED].
 */
enum class AccountDeletionError {
    /** The password typed is not the account's. */
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
     * A deletion went out, this attempt's or an earlier one's, but its answer never came back, so
     * the account may be gone already. Trying again finds out.
     */
    UNCONFIRMED,
}

@StringRes
fun AccountDeletionError.messageRes(): Int = when (this) {
    AccountDeletionError.WRONG_PASSWORD -> R.string.account_deletion_error_wrong_password
    AccountDeletionError.NO_INTERNET -> R.string.account_deletion_error_no_internet
    AccountDeletionError.TIMEOUT -> R.string.account_deletion_error_timeout
    AccountDeletionError.SERVER_UNREACHABLE -> R.string.account_deletion_error_server_unreachable
    AccountDeletionError.UNKNOWN -> R.string.account_deletion_error_unknown
    AccountDeletionError.UNCONFIRMED -> R.string.account_deletion_error_unconfirmed
}
