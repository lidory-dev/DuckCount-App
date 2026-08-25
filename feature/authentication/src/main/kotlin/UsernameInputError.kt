import androidx.annotation.StringRes
import app.lidory.duckcount.authentication.R

/**
 * Why a username cannot be used as typed, at a granularity the UI can explain.
 * The rules themselves live in `lib-duckcount`, composeApp maps them onto this.
 */
enum class UsernameInputError {
    /** Nothing was typed. */
    EMPTY,

    /** The username does not start with a letter. */
    MUST_START_WITH_LETTER,

    /** The username holds something other than letters and digits. */
    INVALID_CHARACTER,
}

@StringRes
fun UsernameInputError.messageRes(): Int = when (this) {
    UsernameInputError.EMPTY -> R.string.username_error_empty
    UsernameInputError.MUST_START_WITH_LETTER -> R.string.username_error_must_start_with_letter
    UsernameInputError.INVALID_CHARACTER -> R.string.username_error_invalid_character
}
