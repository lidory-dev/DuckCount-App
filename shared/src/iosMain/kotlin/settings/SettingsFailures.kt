package settings

import account.AccountDeletionError
import account.PasswordChangeError
import discoveryUsername.DiscoveryUsernameError
import profile.ProfileError
import util.NetworkError
import vault.VaultError

// What the iOS settings screens tell apart, as Android's `model` enums of `:feature:settings` do,
// which live in an Android-only module, and the mappings to them, as `SettingsMappers.kt` has them
// in the Android app. A connection that failed is told apart from airplane mode with `isOnline`:
// Ktor's Darwin engine reports both as a connection that failed.

/**
 * Why deleting the account failed. The session is still there in every case, unless the server
 * turned it down meanwhile, which ends it as any expiry does, and so is the account, as far as the
 * attempt could tell, but for [UNCONFIRMED].
 */
enum class AccountDeletionFailure {
    /** The password typed is not the account's. */
    WRONG_PASSWORD,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,

    /**
     * A deletion went out, this attempt's or an earlier one's, but its answer never came back, so
     * the account may be gone already. Trying again finds out.
     */
    UNCONFIRMED,

    UNKNOWN,
}

/**
 * Why changing the password failed. The password is still the one it was in every case but
 * [UNCONFIRMED], and so is the session, unless the server turned it down meanwhile.
 */
enum class PasswordChangeFailure {
    /** The current password typed is not the account's. */
    WRONG_PASSWORD,

    /** The new password turns up in known data breaches, so nothing was sent, see [PasswordChangeRefusal.leakCount]. */
    LEAKED,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,

    /** The change went out but its answer never came back, so the password may have changed already. */
    UNCONFIRMED,

    UNKNOWN,
}

/**
 * Why changing the password failed, [failure], with how many times the new password turns up in
 * known breaches when that is why, for the screen saying so, as registration's does; 0 otherwise.
 */
class PasswordChangeRefusal(val failure: PasswordChangeFailure, val leakCount: Int)

/**
 * Why saving the profile failed. There is no "saved locally instead": the profile lives on the
 * server, and a save that did not reach it did not happen.
 */
enum class ProfileSaveFailure {
    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,

    /** The session can no longer open the profile, or this device no longer holds the root key. */
    UNAUTHORIZED,

    /** Another device changed the vault while this save was in flight; retrying works. */
    CONFLICT,

    /** The full name is longer than the server takes, which the form keeps it from being. */
    FULL_NAME_TOO_LONG,

    UNKNOWN,
}

/** Why the discovery username could not be reserved. */
enum class ReserveUsernameFailure {
    /** Somebody else already holds that username. */
    ALREADY_TAKEN,

    /** The server refused the username itself. */
    INVALID,

    /** The session is no longer valid, or this device no longer holds the root key. */
    UNAUTHORIZED,

    /** Another device changed the account's key material meanwhile: the same username can be reserved again. */
    CONFLICT,

    /** Too many reservations in too short a time. */
    RATE_LIMITED,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,
    UNKNOWN,
}

/**
 * Only a wrong password, the network's failures and a deletion whose answer never came back are
 * told apart, the ones the user can do something about. Not a `401`: one the refresh found the
 * server turning the session down for has ended the session, which the app restarts on.
 */
internal fun AccountDeletionError.toFailure(isOnline: Boolean): AccountDeletionFailure = when (this) {
    AccountDeletionError.WrongPassword -> AccountDeletionFailure.WRONG_PASSWORD

    is AccountDeletionError.Network -> when (error) {
        NetworkError.NO_INTERNET -> AccountDeletionFailure.NO_INTERNET
        NetworkError.REQUEST_TIMEOUT -> AccountDeletionFailure.TIMEOUT
        NetworkError.UNREACHABLE ->
            if (isOnline) AccountDeletionFailure.SERVER_UNREACHABLE else AccountDeletionFailure.NO_INTERNET

        NetworkError.BAD_REQUEST,
        NetworkError.UNAUTHORIZED,
        NetworkError.NOT_FOUND,
        NetworkError.CONFLICT,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        NetworkError.TOO_MANY_REQUESTS,
        NetworkError.SERVER_ERROR,
        NetworkError.SERIALIZATION,
        NetworkError.FORBIDDEN,
        NetworkError.UNKNOWN,
            -> AccountDeletionFailure.UNKNOWN
    }

    is AccountDeletionError.Unconfirmed -> AccountDeletionFailure.UNCONFIRMED

    AccountDeletionError.Crypto -> AccountDeletionFailure.UNKNOWN
}

/**
 * Only a wrong current password, a leaked new one, the network's failures and a change whose answer
 * never came back are told apart. Not a `401`, as for a deletion's failure, nor a missing root key,
 * which every login and registration stores.
 */
internal fun PasswordChangeError.toRefusal(isOnline: Boolean): PasswordChangeRefusal =
    PasswordChangeRefusal(failure = toFailure(isOnline = isOnline), leakCount = (this as? PasswordChangeError.Leaked)?.count ?: 0)

private fun PasswordChangeError.toFailure(isOnline: Boolean): PasswordChangeFailure = when (this) {
    PasswordChangeError.WrongPassword -> PasswordChangeFailure.WRONG_PASSWORD

    is PasswordChangeError.Leaked -> PasswordChangeFailure.LEAKED

    is PasswordChangeError.Network -> when (error) {
        NetworkError.NO_INTERNET -> PasswordChangeFailure.NO_INTERNET
        NetworkError.REQUEST_TIMEOUT -> PasswordChangeFailure.TIMEOUT
        NetworkError.UNREACHABLE ->
            if (isOnline) PasswordChangeFailure.SERVER_UNREACHABLE else PasswordChangeFailure.NO_INTERNET

        NetworkError.BAD_REQUEST,
        NetworkError.UNAUTHORIZED,
        NetworkError.NOT_FOUND,
        NetworkError.CONFLICT,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        NetworkError.TOO_MANY_REQUESTS,
        NetworkError.SERVER_ERROR,
        NetworkError.SERIALIZATION,
        NetworkError.FORBIDDEN,
        NetworkError.UNKNOWN,
            -> PasswordChangeFailure.UNKNOWN
    }

    is PasswordChangeError.Unconfirmed -> PasswordChangeFailure.UNCONFIRMED

    PasswordChangeError.MissingRootKey,
    PasswordChangeError.Crypto,
        -> PasswordChangeFailure.UNKNOWN
}

/**
 * The vault half is folded in: to the user, "the key could not be reached" and "the profile could
 * not be sent" are the same failed save. Only the two the user can act on are told apart, logging
 * in again or retrying.
 */
internal fun ProfileError.toSaveFailure(isOnline: Boolean): ProfileSaveFailure = when (this) {
    is ProfileError.Network -> error.toSaveFailure(isOnline = isOnline)

    is ProfileError.Vault -> when (val vaultError = error) {
        // Only ever unsealed during a registration or a login, so logging in again is the only way back.
        VaultError.MissingRootKey -> ProfileSaveFailure.UNAUTHORIZED

        VaultError.Contended -> ProfileSaveFailure.CONFLICT

        is VaultError.Network -> vaultError.error.toSaveFailure(isOnline = isOnline)

        VaultError.Crypto,
        VaultError.MalformedKeyRing,
        VaultError.MalformedContactBook,
        VaultError.MalformedMemberProfileBook,
        VaultError.MalformedBlockList,
        VaultError.OrphanedEntry,
            -> ProfileSaveFailure.UNKNOWN
    }

    ProfileError.FullNameTooLong -> ProfileSaveFailure.FULL_NAME_TOO_LONG

    ProfileError.NotStored,
    ProfileError.Crypto,
        -> ProfileSaveFailure.UNKNOWN
}

private fun NetworkError.toSaveFailure(isOnline: Boolean): ProfileSaveFailure = when (this) {
    NetworkError.NO_INTERNET -> ProfileSaveFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ProfileSaveFailure.TIMEOUT
    NetworkError.UNREACHABLE -> if (isOnline) ProfileSaveFailure.SERVER_UNREACHABLE else ProfileSaveFailure.NO_INTERNET
    NetworkError.UNAUTHORIZED -> ProfileSaveFailure.UNAUTHORIZED
    NetworkError.CONFLICT -> ProfileSaveFailure.CONFLICT

    NetworkError.BAD_REQUEST,
    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> ProfileSaveFailure.UNKNOWN
}

/**
 * The vault half is folded in: to the user, "the handle could not be recorded" and "the handle
 * could not be reserved" are the same failed reservation, and retrying is the answer to both.
 */
internal fun DiscoveryUsernameError.toReserveFailure(isOnline: Boolean): ReserveUsernameFailure = when (this) {
    is DiscoveryUsernameError.Reservation -> error.toReserveFailure(isOnline = isOnline)

    is DiscoveryUsernameError.Vault -> when (val vaultError = error) {
        // Only ever unsealed during a registration or a login, so logging in again is the only way back.
        VaultError.MissingRootKey -> ReserveUsernameFailure.UNAUTHORIZED

        VaultError.Contended -> ReserveUsernameFailure.CONFLICT

        is VaultError.Network -> when (vaultError.error) {
            // Not the reservation route's 409, which means the handle is somebody else's: a vault
            // write only ever conflicts on the key ring.
            NetworkError.CONFLICT -> ReserveUsernameFailure.CONFLICT

            else -> vaultError.error.toReserveFailure(isOnline = isOnline)
        }

        VaultError.Crypto,
        VaultError.MalformedKeyRing,
        VaultError.MalformedContactBook,
        VaultError.MalformedMemberProfileBook,
        VaultError.MalformedBlockList,
        VaultError.OrphanedEntry,
            -> ReserveUsernameFailure.UNKNOWN
    }
}

private fun NetworkError.toReserveFailure(isOnline: Boolean): ReserveUsernameFailure = when (this) {
    NetworkError.NO_INTERNET -> ReserveUsernameFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ReserveUsernameFailure.TIMEOUT
    NetworkError.UNREACHABLE -> if (isOnline) ReserveUsernameFailure.SERVER_UNREACHABLE else ReserveUsernameFailure.NO_INTERNET
    NetworkError.CONFLICT -> ReserveUsernameFailure.ALREADY_TAKEN

    NetworkError.BAD_REQUEST,
    NetworkError.UNPROCESSABLE_ENTITY,
        -> ReserveUsernameFailure.INVALID

    NetworkError.UNAUTHORIZED -> ReserveUsernameFailure.UNAUTHORIZED
    NetworkError.TOO_MANY_REQUESTS -> ReserveUsernameFailure.RATE_LIMITED

    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> ReserveUsernameFailure.UNKNOWN
}
