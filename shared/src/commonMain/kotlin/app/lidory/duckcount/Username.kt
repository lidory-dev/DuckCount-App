package app.lidory.duckcount

import uniffi.lib_duckcount.UsernameException
import uniffi.lib_duckcount.hashDiscoveryUsername
import uniffi.lib_duckcount.hashLoginUsername as hashLoginUsernameUniffi
import uniffi.lib_duckcount.normalizeUsername as normalizeUsernameUniffi
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
fun hashUsername(username: String): String? {
    return try {
        hashDiscoveryUsername(username = username)
    } catch (_: UsernameException) {
        null
    }
}

/**
 * Hashes the OPAQUE login username into the digest the server knows the account by.
 *
 * Anything the user typed is accepted, a login username is a credential identifier
 * rather than a handle peers have to reproduce.
 */
fun hashLoginUsername(username: String): String = hashLoginUsernameUniffi(username = username)

private fun UsernameException.toUsernameError(): UsernameError = when (this) {
    is UsernameException.Empty -> UsernameError.EMPTY
    is UsernameException.MustStartWithLetter -> UsernameError.MUST_START_WITH_LETTER
    is UsernameException.InvalidCharacter -> UsernameError.INVALID_CHARACTER
}
