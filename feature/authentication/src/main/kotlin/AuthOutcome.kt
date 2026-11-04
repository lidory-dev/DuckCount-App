import androidx.annotation.StringRes
import app.lidory.duckcount.authentication.R

/**
 * Result of an authentication operation that crosses the [AuthenticationCallBacks] boundary.
 */
sealed interface AuthOutcome<out T> {
    data class Success<out T>(val data: T) : AuthOutcome<T>
    data class Failure(val error: AuthError) : AuthOutcome<Nothing>
}

/** User-facing categories of authentication failure. */
enum class AuthError {
    NO_INTERNET,
    UNREACHABLE,
    USERNAME_TAKEN,
    INVALID_CREDENTIALS,
    TIMEOUT,
    SERVER_ERROR,
    CRYPTO_ERROR,

    /** The full name is longer than the server takes, which the form keeps it from being. */
    FULL_NAME_TOO_LONG,

    /** The server limits logins and registrations per IP address, which can be shared. */
    TOO_MANY_ATTEMPTS,

    /**
     * The server refused the phone's attestation, so no account can be created on it, which is a
     * screen of its own rather than an error on the form, see [DeviceNotAllowedScreen].
     */
    DEVICE_NOT_ALLOWED,

    /** The phone could not produce the attestation the server asks for, as trying again may change. */
    DEVICE_CHECK_FAILED,
    UNKNOWN,
}

/**
 * Maps an [AuthError] to a localized, user-facing message resource.
 *
 * Resolved in composables (via `stringResource`) so ViewModels stay free of Android `R`
 * references and Context.
 */
@StringRes
fun AuthError.messageRes(): Int = when (this) {
    AuthError.NO_INTERNET -> R.string.error_no_internet
    AuthError.UNREACHABLE -> R.string.error_unreachable
    AuthError.USERNAME_TAKEN -> R.string.error_username_taken
    AuthError.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
    AuthError.TIMEOUT -> R.string.error_timeout
    AuthError.SERVER_ERROR -> R.string.error_server
    AuthError.CRYPTO_ERROR -> R.string.error_crypto
    AuthError.FULL_NAME_TOO_LONG -> R.string.error_full_name_too_long
    AuthError.TOO_MANY_ATTEMPTS -> R.string.error_too_many_attempts
    AuthError.DEVICE_NOT_ALLOWED -> R.string.error_device_not_allowed
    AuthError.DEVICE_CHECK_FAILED -> R.string.error_device_check_failed
    AuthError.UNKNOWN -> R.string.error_unknown
}
