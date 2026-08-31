package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/**
 * Why a username cannot be reserved as typed, at a granularity the UI can explain.
 * The rules themselves live in `lib-duckcount`, composeApp maps them onto this.
 */
enum class UsernameInputError {
    /** Nothing was typed. */
    EMPTY,

    /** The username does not start with a letter. */
    MUST_START_WITH_LETTER,

    /** The username holds something other than letters and digits. */
    INVALID_CHARACTER,

    /** The username is longer than the client allows. */
    TOO_LONG,
}

/**
 * The message for [UsernameInputError.TOO_LONG] takes the current and maximum lengths as
 * format arguments; the others carry no placeholder and ignore them.
 */
@StringRes
fun UsernameInputError.messageRes(): Int = when (this) {
    UsernameInputError.EMPTY -> R.string.discovery_username_error_empty
    UsernameInputError.MUST_START_WITH_LETTER -> R.string.discovery_username_error_must_start_with_letter
    UsernameInputError.INVALID_CHARACTER -> R.string.discovery_username_error_invalid_character
    UsernameInputError.TOO_LONG -> R.string.discovery_username_error_too_long
}
