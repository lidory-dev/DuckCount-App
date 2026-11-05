package model

import androidx.annotation.StringRes
import app.lidory.contactList.R

/**
 * Why a contact request could not be sent, at a granularity the UI can explain.
 * The composeApp callback impl maps the shared error onto this.
 */
enum class AddContactError {
    /** The username is not one anybody could have reserved. */
    INVALID_USERNAME,

    /**
     * Nobody reserved that username, or its holder cannot be reached yet. The server
     * answers both the same way.
     */
    NOT_FOUND,

    /** The username is the user's own. */
    SELF,

    /** The username belongs to one of the user's contacts already. */
    ALREADY_CONTACT,

    /** A request to this user is already waiting for their answer. */
    ALREADY_PENDING,

    /** Too many lookups or requests in too short a time (429). */
    RATE_LIMITED,

    /**
     * The recipient presented a security key other than the one this device saw for them
     * before, which is either a new identity or an impersonation attempt.
     */
    IDENTITY_CHANGED,

    /** The user blocked this user, and has to unblock them first. */
    BLOCKED,

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
fun AddContactError.messageRes(): Int = when (this) {
    AddContactError.INVALID_USERNAME -> R.string.add_contact_error_invalid_username
    AddContactError.NOT_FOUND -> R.string.add_contact_error_not_found
    AddContactError.SELF -> R.string.add_contact_error_self
    AddContactError.ALREADY_CONTACT -> R.string.add_contact_error_already_contact
    AddContactError.ALREADY_PENDING -> R.string.add_contact_error_already_pending
    AddContactError.RATE_LIMITED -> R.string.add_contact_error_rate_limited
    AddContactError.IDENTITY_CHANGED -> R.string.add_contact_error_identity_changed
    AddContactError.BLOCKED -> R.string.add_contact_error_blocked
    AddContactError.NO_INTERNET -> R.string.add_contact_error_no_internet
    AddContactError.TIMEOUT -> R.string.add_contact_error_timeout
    AddContactError.SERVER_UNREACHABLE -> R.string.add_contact_error_server_unreachable
    AddContactError.UNKNOWN -> R.string.add_contact_error_unknown
}
