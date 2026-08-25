package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/**
 * Why reserving a username failed, at a granularity the UI can explain.
 * The composeApp callback impl maps the low-level networking error onto this.
 */
enum class ReserveUsernameError {
    /** Somebody else already holds that username. */
    ALREADY_TAKEN,

    /** The server rejected the username itself. */
    INVALID,

    /** The session is no longer valid (401). */
    UNAUTHORIZED,

    /**
     * Too many reservations in too short a time (429).
     *
     * The server meters this route tightly (a handful a minute, a couple of dozen a day)
     * because the "already taken" answer would otherwise let a caller walk a dictionary
     * of hashed identifiers and enumerate who is registered.
     */
    RATE_LIMITED,

    /** No connectivity / timed out. */
    NO_INTERNET,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** Anything else (server error, unexpected response, …). */
    UNKNOWN,
}

@StringRes
fun ReserveUsernameError.messageRes(): Int = when (this) {
    ReserveUsernameError.ALREADY_TAKEN -> R.string.reserve_error_already_taken
    ReserveUsernameError.INVALID -> R.string.reserve_error_invalid
    ReserveUsernameError.UNAUTHORIZED -> R.string.reserve_error_unauthorized
    ReserveUsernameError.RATE_LIMITED -> R.string.reserve_error_rate_limited
    ReserveUsernameError.NO_INTERNET -> R.string.reserve_error_no_internet
    ReserveUsernameError.SERVER_UNREACHABLE -> R.string.reserve_error_server_unreachable
    ReserveUsernameError.UNKNOWN -> R.string.reserve_error_unknown
}
