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
    AuthError.UNKNOWN -> R.string.error_unknown
}
