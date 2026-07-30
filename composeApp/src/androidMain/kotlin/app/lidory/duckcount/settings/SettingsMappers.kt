package app.lidory.duckcount.settings

import app.lidory.duckcount.settings.model.ReserveUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import util.NetworkError
import app.lidory.duckcount.UsernameError as SharedUsernameError

fun SharedUsernameError.toUsernameInputError(): UsernameInputError = when (this) {
    SharedUsernameError.EMPTY -> UsernameInputError.EMPTY
    SharedUsernameError.MUST_START_WITH_LETTER -> UsernameInputError.MUST_START_WITH_LETTER
    SharedUsernameError.INVALID_CHARACTER -> UsernameInputError.INVALID_CHARACTER
}

/**
 * Maps the low-level networking error to the reason the UI surfaces for a failed
 * username reservation.
 *
 * The "couldn't connect" case ([NetworkError.UNREACHABLE]) is resolved against real device
 * connectivity ([isOnline]): airplane mode can surface as UNREACHABLE rather than NO_INTERNET
 * and must be corrected here.
 */
fun NetworkError.toReserveUsernameError(isOnline: Boolean): ReserveUsernameError = when (this) {
    NetworkError.NO_INTERNET,
    NetworkError.REQUEST_TIMEOUT,
        -> ReserveUsernameError.NO_INTERNET

    NetworkError.UNREACHABLE ->
        if (isOnline) ReserveUsernameError.SERVER_UNREACHABLE else ReserveUsernameError.NO_INTERNET

    NetworkError.CONFLICT -> ReserveUsernameError.ALREADY_TAKEN

    NetworkError.BAD_REQUEST,
    NetworkError.UNPROCESSABLE_ENTITY,
        -> ReserveUsernameError.INVALID

    NetworkError.UNAUTHORIZED -> ReserveUsernameError.UNAUTHORIZED

    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> ReserveUsernameError.UNKNOWN
}
