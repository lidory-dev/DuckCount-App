package model

import androidx.annotation.StringRes
import app.lidory.contactList.R

/**
 * Why a contact request could not be accepted or refused, at a granularity the UI can
 * explain. The composeApp callback impl maps the shared error onto this.
 */
enum class AnswerContactRequestError {
    /** The request is no longer waiting for the user. */
    NOT_FOUND,

    /** No connectivity / timed out. */
    NO_INTERNET,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** Anything else (server error, unusable key material, …). */
    UNKNOWN,
}

@StringRes
fun AnswerContactRequestError.messageRes(): Int = when (this) {
    AnswerContactRequestError.NOT_FOUND -> R.string.answer_contact_request_error_not_found
    AnswerContactRequestError.NO_INTERNET -> R.string.answer_contact_request_error_no_internet
    AnswerContactRequestError.SERVER_UNREACHABLE -> R.string.answer_contact_request_error_server_unreachable
    AnswerContactRequestError.UNKNOWN -> R.string.answer_contact_request_error_unknown
}
