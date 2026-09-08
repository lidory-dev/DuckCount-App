package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/**
 * Why reserving a discovery username failed, at a granularity the UI can explain.
 * The composeApp callback impl maps the low-level networking error onto this.
 */
enum class ReserveDiscoveryUsernameError {
    /** Somebody else already holds that username. */
    ALREADY_TAKEN,

    /** The server rejected the username itself. */
    INVALID,

    /** The session is no longer valid (401). */
    UNAUTHORIZED,

    /**
     * Another device changed the account's key material while this reservation was
     * being written, so the handle was not recorded.
     *
     * Distinct from [ALREADY_TAKEN]: the username is free, the write lost a race and
     * the same one can simply be reserved again.
     */
    CONFLICT,

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
fun ReserveDiscoveryUsernameError.messageRes(): Int = when (this) {
    ReserveDiscoveryUsernameError.ALREADY_TAKEN -> R.string.reserve_error_already_taken
    ReserveDiscoveryUsernameError.INVALID -> R.string.reserve_error_invalid
    ReserveDiscoveryUsernameError.UNAUTHORIZED -> R.string.reserve_error_unauthorized
    ReserveDiscoveryUsernameError.CONFLICT -> R.string.reserve_error_conflict
    ReserveDiscoveryUsernameError.RATE_LIMITED -> R.string.reserve_error_rate_limited
    ReserveDiscoveryUsernameError.NO_INTERNET -> R.string.reserve_error_no_internet
    ReserveDiscoveryUsernameError.SERVER_UNREACHABLE -> R.string.reserve_error_server_unreachable
    ReserveDiscoveryUsernameError.UNKNOWN -> R.string.reserve_error_unknown
}
