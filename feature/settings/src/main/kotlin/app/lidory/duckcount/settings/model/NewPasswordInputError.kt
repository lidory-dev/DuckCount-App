package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/** Why a new password cannot be set as typed, before anything is sent. */
enum class NewPasswordInputError {
    /** Shorter than the minimum length. */
    TOO_SHORT,

    /** The current password typed again, which changing to would change nothing. */
    SAME_AS_CURRENT,
}

/**
 * The message for [NewPasswordInputError.TOO_SHORT] takes the minimum length as a format
 * argument; the other carries no placeholder and ignores it.
 */
@StringRes
fun NewPasswordInputError.messageRes(): Int = when (this) {
    NewPasswordInputError.TOO_SHORT -> R.string.error_password_too_short
    NewPasswordInputError.SAME_AS_CURRENT -> R.string.password_change_error_same_password
}
