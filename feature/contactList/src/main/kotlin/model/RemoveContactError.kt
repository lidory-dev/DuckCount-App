package model

import androidx.annotation.StringRes
import app.lidory.contactList.R

/**
 * Why a contact could not be removed, at a granularity the UI can explain. The composeApp
 * callback impl maps the shared error onto this.
 */
enum class RemoveContactError {
    /** Too many messages sent in too short a time (429). */
    RATE_LIMITED,

    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** Anything else (server error, unusable key material, …). */
    UNKNOWN,
}

@StringRes
fun RemoveContactError.messageRes(): Int = when (this) {
    RemoveContactError.RATE_LIMITED -> R.string.remove_contact_error_rate_limited
    RemoveContactError.NO_INTERNET -> R.string.remove_contact_error_no_internet
    RemoveContactError.TIMEOUT -> R.string.remove_contact_error_timeout
    RemoveContactError.SERVER_UNREACHABLE -> R.string.remove_contact_error_server_unreachable
    RemoveContactError.UNKNOWN -> R.string.remove_contact_error_unknown
}
