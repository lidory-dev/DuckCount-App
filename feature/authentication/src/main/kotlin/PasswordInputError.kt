import androidx.annotation.StringRes
import app.lidory.duckcount.authentication.R

/** Why a password cannot be registered as typed, before anything is sent. */
enum class PasswordInputError {
    /**
     * Nothing but whitespace, which no form takes for a password: logging in with it would never
     * be offered, see [viewmodel.LoginScreenState.canLogIn].
     */
    BLANK,

    /** Shorter than the minimum length. */
    TOO_SHORT,
}

/**
 * The message for [PasswordInputError.TOO_SHORT] takes the minimum length as a format argument;
 * the other carries no placeholder and ignores it.
 */
@StringRes
fun PasswordInputError.messageRes(): Int = when (this) {
    PasswordInputError.BLANK -> R.string.error_password_blank
    PasswordInputError.TOO_SHORT -> R.string.error_password_short
}
