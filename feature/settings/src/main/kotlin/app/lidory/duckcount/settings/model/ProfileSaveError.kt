package app.lidory.duckcount.settings.model

import androidx.annotation.StringRes
import app.lidory.duckcount.settings.R

/**
 * Why saving the profile failed, at a granularity the UI can explain.
 * The composeApp callback impl maps the low-level error onto this.
 *
 * There is no "saved locally instead" case, by design the profile lives on the server
 * and a save that did not reach it did not happen.
 */
enum class ProfileSaveError {
    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /**
     * The session can no longer open the profile (401), or this device no longer holds
     * the root key everything else is sealed under. Both are fixed by logging in again.
     */
    UNAUTHORIZED,

    /** Another device changed the vault while this save was in flight; retrying works. */
    CONFLICT,

    /** The full name is longer than the server takes, which the form keeps it from being. */
    FULL_NAME_TOO_LONG,

    /** Anything else (server error, key material that will not unseal, …). */
    UNKNOWN,
}

@StringRes
fun ProfileSaveError.messageRes(): Int = when (this) {
    ProfileSaveError.NO_INTERNET -> R.string.profile_error_no_internet
    ProfileSaveError.TIMEOUT -> R.string.profile_error_timeout
    ProfileSaveError.SERVER_UNREACHABLE -> R.string.profile_error_server_unreachable
    ProfileSaveError.UNAUTHORIZED -> R.string.profile_error_unauthorized
    ProfileSaveError.CONFLICT -> R.string.profile_error_conflict
    ProfileSaveError.FULL_NAME_TOO_LONG -> R.string.profile_error_full_name_too_long
    ProfileSaveError.UNKNOWN -> R.string.profile_error_unknown
}
