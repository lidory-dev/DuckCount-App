package authentication

import util.NetworkError

/**
 * Why logging in, registering or setting up the profile failed, at the granularity the iOS screens
 * tell apart: the same as Android's `AuthError`, which lives in an Android-only module.
 */
enum class AuthenticationError {
    NO_INTERNET,
    UNREACHABLE,
    USERNAME_TAKEN,
    INVALID_CREDENTIALS,
    TIMEOUT,
    SERVER_ERROR,
    CRYPTO_ERROR,

    /** The full name is longer than the server takes, which the form keeps it from being. */
    FULL_NAME_TOO_LONG,

    /** The server limits logins and registrations per IP address, which can be shared. */
    TOO_MANY_ATTEMPTS,

    /**
     * The server refused the device's attestation, so no account can be created on it, which is a
     * screen of its own rather than an error on the form.
     */
    DEVICE_NOT_ALLOWED,

    /** App Attest could not attest the device, as trying again may change. */
    DEVICE_CHECK_FAILED,
    UNKNOWN,
}

/**
 * Maps a transport-level [NetworkError] to an [AuthenticationError], as Android's `toAuthError`
 * does. The "couldn't connect" case is resolved against the device's connectivity ([isOnline]):
 * Ktor's Darwin engine reports a phone in airplane mode as a connection that failed, never as an
 * address it could not resolve.
 */
internal fun NetworkError.toAuthenticationError(isOnline: Boolean): AuthenticationError = when (this) {
    NetworkError.NO_INTERNET -> AuthenticationError.NO_INTERNET
    NetworkError.UNREACHABLE ->
        if (isOnline) AuthenticationError.UNREACHABLE else AuthenticationError.NO_INTERNET

    NetworkError.CONFLICT -> AuthenticationError.USERNAME_TAKEN
    // A login session that ran out (404) and "unauthorized" both read as invalid credentials to
    // the user.
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
        -> AuthenticationError.INVALID_CREDENTIALS

    NetworkError.REQUEST_TIMEOUT -> AuthenticationError.TIMEOUT
    NetworkError.SERVER_ERROR -> AuthenticationError.SERVER_ERROR
    NetworkError.TOO_MANY_REQUESTS -> AuthenticationError.TOO_MANY_ATTEMPTS
    NetworkError.BAD_REQUEST,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERIALIZATION,
    // Only the answer to a registration means anything by it, see [toRegistrationError]: logging
    // in is not gated on the device.
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> AuthenticationError.UNKNOWN
}

/**
 * Maps any failed request of a registration to an [AuthenticationError], which a `403` makes a
 * device the server would not create an account on, a `404` a challenge or a session that ran
 * out, with no password to have got wrong, and the rest makes what it is anywhere else.
 */
internal fun NetworkError.toRegistrationError(isOnline: Boolean): AuthenticationError = when (this) {
    NetworkError.FORBIDDEN -> AuthenticationError.DEVICE_NOT_ALLOWED
    NetworkError.NOT_FOUND -> AuthenticationError.UNKNOWN
    else -> toAuthenticationError(isOnline = isOnline)
}
