package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/** Why a new password cannot be set as typed, before anything is sent. */
enum class NewPasswordInputError {
    /**
     * Nothing but whitespace, which no form takes for a password, registration included: logging
     * in with it would never be offered.
     */
    BLANK,

    /** Shorter than the minimum length. */
    TOO_SHORT,

    /** The current password typed again, which changing to would change nothing. */
    SAME_AS_CURRENT,
}

/**
 * The message for [NewPasswordInputError.TOO_SHORT] takes the minimum length as a format
 * argument; the others carry no placeholder and ignore it.
 */
@StringRes
fun NewPasswordInputError.messageRes(): Int = when (this) {
    NewPasswordInputError.BLANK -> R.string.error_new_password_blank
    NewPasswordInputError.TOO_SHORT -> R.string.error_password_too_short
    NewPasswordInputError.SAME_AS_CURRENT -> R.string.password_change_error_same_password
}
