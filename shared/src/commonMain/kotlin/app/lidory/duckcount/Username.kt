package app.lidory.duckcount

import uniffi.lib_duckcount.UsernameException
import uniffi.lib_duckcount.hashDiscoveryUsername
import uniffi.lib_duckcount.hashLoginUsername as hashLoginUsernameUniffi
import uniffi.lib_duckcount.normalizeUsername as normalizeUsernameUniffi
import uniffi.lib_duckcount.usernameMaximumLength as usernameMaximumLengthUniffi
import uniffi.lib_duckcount.validateUsername as validateUsernameUniffi

/**
 * Checks a username the way the Rust library does, and returns why it is invalid,
 * or null when it is valid.
 */
fun validateUsername(username: String): UsernameError? {
    return try {
        validateUsernameUniffi(username = username)
        null
    } catch (e: UsernameException) {
        e.toUsernameError()
    }
}

/**
 * The longest username the library accepts, so the UI can count against the very limit
 * [validateUsername] enforces rather than repeating the number.
 */
fun usernameMaximumLength(): Int = usernameMaximumLengthUniffi().toInt()

/**
 * Returns the normalized form of [username] (trimmed and lowercased).
 *
 * This is what gets stored locally: the digest sent to the server is one-way, so the
 * handle itself has to be kept if we ever want to show it back to the user.
 */
fun normalizeUsername(username: String): String {
    return normalizeUsernameUniffi(username = username)
}

/**
 * Hashes [username] into the digest the server stores as a reserved username, or
 * returns null when the username is not valid.
 */
fun hashDiscoveryUsername(username: String): String? {
    return try {
        hashDiscoveryUsername(username = username)
    } catch (_: UsernameException) {
        null
    }
}

/**
 * Hashes the OPAQUE login username into the digest the server knows the account by.
 *
 * Throws [UsernameException] on a username the library rejects, so call [validateUsername]
 * first: unlike a discovery username, this one is a credential identifier rather than a
 * handle peers have to reproduce, and there is no digest to fall back on.
 */
fun hashLoginUsername(username: String): String = hashLoginUsernameUniffi(username = username)

private fun UsernameException.toUsernameError(): UsernameError = when (this) {
    is UsernameException.Empty -> UsernameError.EMPTY
    is UsernameException.MustStartWithLetter -> UsernameError.MUST_START_WITH_LETTER
    is UsernameException.InvalidCharacter -> UsernameError.INVALID_CHARACTER
    is UsernameException.TooLong -> UsernameError.TOO_LONG
}
