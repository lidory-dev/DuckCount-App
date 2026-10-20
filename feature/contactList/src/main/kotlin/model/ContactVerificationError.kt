package model

import androidx.annotation.StringRes
import app.lidory.contactList.R

/**
 * Why the safety number of a contact could not be computed, at a granularity the UI can
 * explain. The composeApp callback impl maps the shared error onto this.
 */
enum class ContactVerificationError {
    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** Anything else (unusable key material, storage failure, …). */
    UNKNOWN,
}

@StringRes
fun ContactVerificationError.messageRes(): Int = when (this) {
    ContactVerificationError.NO_INTERNET -> R.string.contact_verification_error_no_internet
    ContactVerificationError.TIMEOUT -> R.string.contact_verification_error_timeout
    ContactVerificationError.SERVER_UNREACHABLE -> R.string.contact_verification_error_server_unreachable
    ContactVerificationError.UNKNOWN -> R.string.contact_verification_error_unknown
}
