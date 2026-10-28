package app.lidory.duckcount.settings

import app.lidory.duckcount.settings.model.AccountDeletionError
import app.lidory.duckcount.settings.model.PasswordChangeError
import app.lidory.duckcount.settings.model.ProfileSaveError
import app.lidory.duckcount.settings.model.ReserveDiscoveryUsernameError
import app.lidory.duckcount.settings.model.UsernameInputError
import discoveryUsername.DiscoveryUsernameError
import profile.ProfileError
import util.NetworkError
import vault.VaultError
import account.AccountDeletionError as SharedAccountDeletionError
import account.PasswordChangeError as SharedPasswordChangeError
import app.lidory.duckcount.UsernameError as SharedUsernameError

/** An empty username is no mistake to point out, see [SettingsCallBacks.validateUsername]. */
fun SharedUsernameError.toUsernameInputError(): UsernameInputError? = when (this) {
    SharedUsernameError.EMPTY -> null
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
fun NetworkError.toReserveUsernameError(isOnline: Boolean): ReserveDiscoveryUsernameError = when (this) {
    NetworkError.NO_INTERNET -> ReserveDiscoveryUsernameError.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ReserveDiscoveryUsernameError.TIMEOUT

    NetworkError.UNREACHABLE ->
        if (isOnline) ReserveDiscoveryUsernameError.SERVER_UNREACHABLE else ReserveDiscoveryUsernameError.NO_INTERNET

    NetworkError.CONFLICT -> ReserveDiscoveryUsernameError.ALREADY_TAKEN

    NetworkError.BAD_REQUEST,
    NetworkError.UNPROCESSABLE_ENTITY,
        -> ReserveDiscoveryUsernameError.INVALID

    NetworkError.UNAUTHORIZED -> ReserveDiscoveryUsernameError.UNAUTHORIZED

    NetworkError.TOO_MANY_REQUESTS -> ReserveDiscoveryUsernameError.RATE_LIMITED

    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> ReserveDiscoveryUsernameError.UNKNOWN
}

/**
 * Maps a failed username reservation to the reason the UI surfaces.
 *
 * The vault half is folded in rather than reported separately: to the user, "the handle
 * could not be recorded" and "the handle could not be reserved" are the same failed
 * reservation, and retrying is the answer to both.
 *
 * As in [toReserveUsernameError], [isOnline] corrects a [NetworkError.UNREACHABLE] that
 * is really airplane mode.
 */
fun DiscoveryUsernameError.toReserveUsernameError(isOnline: Boolean): ReserveDiscoveryUsernameError =
    when (this) {
        is DiscoveryUsernameError.Reservation -> error.toReserveUsernameError(isOnline = isOnline)

        is DiscoveryUsernameError.Vault -> when (val vaultError = error) {
            // Only ever unsealed during a registration or a login, so logging in again is
            // the only way back.
            VaultError.MissingRootKey -> ReserveDiscoveryUsernameError.UNAUTHORIZED

            VaultError.Contended -> ReserveDiscoveryUsernameError.CONFLICT

            is VaultError.Network -> when (vaultError.error) {
                // Not the reservation route's 409, which is the one that means the handle
                // belongs to somebody else. A vault write only ever conflicts on the key
                // ring, and that is already reported as [VaultError.Contended].
                NetworkError.CONFLICT -> ReserveDiscoveryUsernameError.CONFLICT

                else -> vaultError.error.toReserveUsernameError(isOnline = isOnline)
            }

            // Key material that will not unseal or does not parse. Nothing the user can do,
            // and nothing worth explaining to them in detail.
            // TODO: Log this, it should never happen.
            VaultError.Crypto,
            VaultError.MalformedKeyRing,
            VaultError.MalformedContactBook,
            VaultError.MalformedMemberProfileBook,
            VaultError.OrphanedEntry,
                -> ReserveDiscoveryUsernameError.UNKNOWN
        }
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
        VaultError.MalformedContactBook,
        VaultError.MalformedMemberProfileBook,
        VaultError.OrphanedEntry,
            -> ProfileSaveError.UNKNOWN
    }

    ProfileError.NotStored,
    ProfileError.Crypto,
        -> ProfileSaveError.UNKNOWN
}

private fun NetworkError.toProfileSaveError(isOnline: Boolean): ProfileSaveError = when (this) {
    NetworkError.NO_INTERNET -> ProfileSaveError.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ProfileSaveError.TIMEOUT

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

/**
 * Maps a failed account deletion to the reason the UI surfaces.
 *
 * Only a wrong password, the network's failures and a deletion whose answer never came back are
 * told apart, the ones the user can do something about. Not a `401`: one the refresh found the
 * server turning the session down for has ended the session there, restarting the app and telling
 * the user, see the `refreshTokens` block in `DuckCountClient` and its `expireSession`. Any other
 * `401` is the refresh failing for a passing reason, a failure like any other.
 *
 * As in [toReserveUsernameError], [isOnline] corrects a [NetworkError.UNREACHABLE] that
 * is really airplane mode.
 */
fun SharedAccountDeletionError.toAccountDeletionError(isOnline: Boolean): AccountDeletionError = when (this) {
    SharedAccountDeletionError.WrongPassword -> AccountDeletionError.WRONG_PASSWORD

    is SharedAccountDeletionError.Network -> when (error) {
        NetworkError.NO_INTERNET -> AccountDeletionError.NO_INTERNET
        NetworkError.REQUEST_TIMEOUT -> AccountDeletionError.TIMEOUT

        NetworkError.UNREACHABLE ->
            if (isOnline) AccountDeletionError.SERVER_UNREACHABLE else AccountDeletionError.NO_INTERNET

        NetworkError.BAD_REQUEST,
        NetworkError.UNAUTHORIZED,
        NetworkError.NOT_FOUND,
        NetworkError.CONFLICT,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        NetworkError.TOO_MANY_REQUESTS,
        NetworkError.SERVER_ERROR,
        NetworkError.SERIALIZATION,
        NetworkError.UNKNOWN,
            -> AccountDeletionError.UNKNOWN
    }

    is SharedAccountDeletionError.Unconfirmed -> AccountDeletionError.UNCONFIRMED

    SharedAccountDeletionError.Crypto -> AccountDeletionError.UNKNOWN
}

/**
 * Maps a failed password change to the reason the UI surfaces.
 *
 * Only the two mistakes the form can show on a field, the network's failures and a change whose
 * answer never came back are told apart, the ones the user can do something about. Not a `401`,
 * as for [toAccountDeletionError]: one the server turned the session down with has ended it,
 * restarting the app and telling the user. Nor a missing root key, which every login and
 * registration stores, see `authenticationUtils.setRootKey`.
 *
 * As in [toReserveUsernameError], [isOnline] corrects a [NetworkError.UNREACHABLE] that
 * is really airplane mode.
 */
fun SharedPasswordChangeError.toPasswordChangeError(isOnline: Boolean): PasswordChangeError = when (this) {
    SharedPasswordChangeError.WrongPassword -> PasswordChangeError.WRONG_PASSWORD

    SharedPasswordChangeError.Leaked -> PasswordChangeError.LEAKED

    is SharedPasswordChangeError.Network -> when (error) {
        NetworkError.NO_INTERNET -> PasswordChangeError.NO_INTERNET
        NetworkError.REQUEST_TIMEOUT -> PasswordChangeError.TIMEOUT

        NetworkError.UNREACHABLE ->
            if (isOnline) PasswordChangeError.SERVER_UNREACHABLE else PasswordChangeError.NO_INTERNET

        NetworkError.BAD_REQUEST,
        NetworkError.UNAUTHORIZED,
        NetworkError.NOT_FOUND,
        NetworkError.CONFLICT,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        NetworkError.TOO_MANY_REQUESTS,
        NetworkError.SERVER_ERROR,
        NetworkError.SERIALIZATION,
        NetworkError.UNKNOWN,
            -> PasswordChangeError.UNKNOWN
    }

    is SharedPasswordChangeError.Unconfirmed -> PasswordChangeError.UNCONFIRMED

    SharedPasswordChangeError.MissingRootKey,
    SharedPasswordChangeError.Crypto,
        -> PasswordChangeError.UNKNOWN
}
