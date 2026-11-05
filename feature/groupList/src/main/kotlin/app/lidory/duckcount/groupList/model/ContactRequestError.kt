package app.lidory.duckcount.groupList.model

import androidx.annotation.StringRes
import app.lidory.duckcount.groupList.R

/**
 * Why a contact request to a member of the group could not be sent from their sheet, at a
 * granularity the sheet can explain, see
 * [app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks.sendContactRequest]. The
 * composeApp callback impl maps the shared error onto this.
 *
 * The sheet offers to send it again whatever the reason, unless it now tells the member as a
 * contact or as asked already, which [ALREADY_PENDING] has it tell, see
 * [app.lidory.duckcount.groupList.viewmodel.isRequestOut]: one whose sending ended in doubt, a
 * timeout say, is settled by the server then, which refuses the new one as already pending or
 * stores it.
 */
enum class ContactRequestError {
    /**
     * They have nothing to be sent a request with for now, or no longer have an account: the
     * server answers both the same way.
     */
    NOT_FOUND,

    /** They became one of the user's contacts meanwhile. */
    ALREADY_CONTACT,

    /** A request of the user's to them is already waiting for their answer. */
    ALREADY_PENDING,

    /** Too many requests in too short a time (429). */
    RATE_LIMITED,

    /**
     * The identity they hold in the group, or the one the server handed over for them, is not
     * the one this device saw for them before, which is either a new identity or an
     * impersonation attempt.
     */
    IDENTITY_CHANGED,

    /**
     * The group cannot vouch for who they are on this device: it no longer holds them as this
     * device last read it, its state is not on this device, or its creator is blocked. Nothing
     * the group brings later is sure to change it.
     */
    NOT_IN_GROUP,

    /** The user blocked them, and has to unblock them first, from the contacts. */
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
fun ContactRequestError.messageRes(): Int = when (this) {
    ContactRequestError.NOT_FOUND -> R.string.member_contact_request_error_not_found
    ContactRequestError.ALREADY_CONTACT -> R.string.member_contact_request_error_already_contact
    ContactRequestError.ALREADY_PENDING -> R.string.member_contact_request_error_already_pending
    ContactRequestError.RATE_LIMITED -> R.string.member_contact_request_error_rate_limited
    ContactRequestError.IDENTITY_CHANGED -> R.string.member_contact_request_error_identity_changed
    ContactRequestError.NOT_IN_GROUP -> R.string.member_contact_request_error_not_in_group
    ContactRequestError.BLOCKED -> R.string.member_contact_request_error_blocked
    ContactRequestError.NO_INTERNET -> R.string.member_contact_request_error_no_internet
    ContactRequestError.TIMEOUT -> R.string.member_contact_request_error_timeout
    ContactRequestError.SERVER_UNREACHABLE -> R.string.member_contact_request_error_server_unreachable
    ContactRequestError.UNKNOWN -> R.string.member_contact_request_error_unknown
}
