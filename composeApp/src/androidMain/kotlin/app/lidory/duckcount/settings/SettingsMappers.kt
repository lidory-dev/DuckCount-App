package app.lidory.duckcount.settings

import app.lidory.duckcount.settings.model.ProfileSaveError
import app.lidory.duckcount.settings.model.ReserveUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import profile.ProfileError
import util.NetworkError
import vault.VaultError
import app.lidory.duckcount.UsernameError as SharedUsernameError

fun SharedUsernameError.toUsernameInputError(): UsernameInputError = when (this) {
    SharedUsernameError.EMPTY -> UsernameInputError.EMPTY
    SharedUsernameError.MUST_START_WITH_LETTER -> UsernameInputError.MUST_START_WITH_LETTER
    SharedUsernameError.INVALID_CHARACTER -> UsernameInputError.INVALID_CHARACTER
    SharedUsernameError.TOO_LONG -> UsernameInputError.TOO_LONG
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

    NetworkError.TOO_MANY_REQUESTS -> ReserveUsernameError.RATE_LIMITED

    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> ReserveUsernameError.UNKNOWN
}

/**
 * Maps a failed profile save to the reason the UI surfaces.
 *
 * The vault half is folded in rather than reported separately: to the user, "the key
 * could not be reached" and "the profile could not be sent" are the same failed save.
 * Only the two the user can act on are told apart (log in again, or retry).
 *
 * As in [toReserveUsernameError], [isOnline] corrects a [NetworkError.UNREACHABLE] that
 * is really airplane mode.
 */
fun ProfileError.toProfileSaveError(isOnline: Boolean): ProfileSaveError = when (this) {
    is ProfileError.Network -> error.toProfileSaveError(isOnline = isOnline)

    is ProfileError.Vault -> when (val vaultError = error) {
        // Only ever unsealed during a registration or a login, so logging in again is
        // the only way back.
        VaultError.MissingRootKey -> ProfileSaveError.UNAUTHORIZED

        VaultError.Contended -> ProfileSaveError.CONFLICT

        is VaultError.Network -> vaultError.error.toProfileSaveError(isOnline = isOnline)

        // Key material that will not unseal or does not parse. Nothing the user can do,
        // and nothing worth explaining to them in detail.
        // TODO: Log this, it should never happen.
        VaultError.Crypto,
        VaultError.MalformedKeyRing,
        VaultError.OrphanedEntry,
            -> ProfileSaveError.UNKNOWN
    }

    ProfileError.NotStored,
    ProfileError.Crypto,
        -> ProfileSaveError.UNKNOWN
}

private fun NetworkError.toProfileSaveError(isOnline: Boolean): ProfileSaveError = when (this) {
    NetworkError.NO_INTERNET,
    NetworkError.REQUEST_TIMEOUT,
        -> ProfileSaveError.NO_INTERNET

    NetworkError.UNREACHABLE ->
        if (isOnline) ProfileSaveError.SERVER_UNREACHABLE else ProfileSaveError.NO_INTERNET

    NetworkError.UNAUTHORIZED -> ProfileSaveError.UNAUTHORIZED

    NetworkError.CONFLICT -> ProfileSaveError.CONFLICT

    NetworkError.BAD_REQUEST,
    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> ProfileSaveError.UNKNOWN
}
