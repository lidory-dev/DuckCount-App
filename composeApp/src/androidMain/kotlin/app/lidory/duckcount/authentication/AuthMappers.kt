package app.lidory.duckcount.authentication

import AuthError
import util.NetworkError

/**
 * Maps a transport-level [NetworkError] to a user-facing [AuthError]. The "couldn't connect"
 * case ([NetworkError.UNREACHABLE]) is resolved against real device connectivity ([isOnline]), so
 * airplane mode reads as "no internet" even when the base URL is a bare IP (no DNS step to fail
 * on).
 */
fun NetworkError.toAuthError(isOnline: Boolean): AuthError = when (this) {
    NetworkError.NO_INTERNET -> AuthError.NO_INTERNET
    NetworkError.UNREACHABLE ->
        if (isOnline) AuthError.UNREACHABLE else AuthError.NO_INTERNET

    NetworkError.CONFLICT -> AuthError.USERNAME_TAKEN
    // A login session that ran out (404) and "unauthorized" both read as invalid credentials to
    // the user.
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
        -> AuthError.INVALID_CREDENTIALS

    NetworkError.REQUEST_TIMEOUT -> AuthError.TIMEOUT
    NetworkError.SERVER_ERROR -> AuthError.SERVER_ERROR
    NetworkError.TOO_MANY_REQUESTS -> AuthError.TOO_MANY_ATTEMPTS
    NetworkError.BAD_REQUEST,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERIALIZATION,
    // Only the answer to a registration means anything by it, see [toRegistrationError]: logging
    // in is not gated on the phone.
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> AuthError.UNKNOWN
}

/**
 * Maps any failed request of a registration to a user-facing [AuthError], which a `403` makes a
 * phone the server would not create an account on, see [DeviceAttester], a `404` a challenge or a
 * session that ran out, with no password to have got wrong, and the rest makes what it is anywhere
 * else, the `503` of a server that cannot run the check for now among them.
 */
fun NetworkError.toRegistrationError(isOnline: Boolean): AuthError = when (this) {
    NetworkError.FORBIDDEN -> AuthError.DEVICE_NOT_ALLOWED
    NetworkError.NOT_FOUND -> AuthError.UNKNOWN
    else -> toAuthError(isOnline = isOnline)
}
