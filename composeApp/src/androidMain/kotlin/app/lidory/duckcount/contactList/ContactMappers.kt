package app.lidory.duckcount.contactList

import contact.SendContactRequestError
import mls.MlsIdentityError
import model.AddContactError
import model.User
import util.NetworkError
import vault.VaultError
import database.domain.User as UserDB

fun User.toDb(): UserDB = UserDB(
    uuid = uuid,
    username = username,
    fullName = fullName,
)

/**
 * Maps a failed contact request to the reason the UI surfaces.
 *
 * What went wrong with the user's own key material, in the vault or in the MLS client,
 * is folded into [AddContactError.UNKNOWN]: nothing the user can act on, and nothing
 * worth explaining to them in detail. Network failures along the way are reported as
 * such, whichever call they came from.
 *
 * [isOnline] corrects a [NetworkError.UNREACHABLE] that is really airplane mode.
 */
fun SendContactRequestError.toAddContactError(isOnline: Boolean): AddContactError = when (this) {
    SendContactRequestError.InvalidUsername -> AddContactError.INVALID_USERNAME
    SendContactRequestError.NotFound -> AddContactError.NOT_FOUND
    SendContactRequestError.Self -> AddContactError.SELF
    SendContactRequestError.AlreadyPending -> AddContactError.ALREADY_PENDING
    SendContactRequestError.RateLimited -> AddContactError.RATE_LIMITED
    SendContactRequestError.IdentityChanged -> AddContactError.IDENTITY_CHANGED

    is SendContactRequestError.Network -> error.toAddContactError(isOnline = isOnline)

    is SendContactRequestError.Vault -> error.toAddContactError(isOnline = isOnline)

    is SendContactRequestError.Identity -> when (val identityError = error) {
        is MlsIdentityError.Vault -> identityError.error.toAddContactError(isOnline = isOnline)

        MlsIdentityError.MissingSession,
        MlsIdentityError.Crypto,
            -> AddContactError.UNKNOWN
    }

    SendContactRequestError.Crypto -> AddContactError.UNKNOWN
}

private fun VaultError.toAddContactError(isOnline: Boolean): AddContactError = when (this) {
    is VaultError.Network -> error.toAddContactError(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> AddContactError.UNKNOWN
}

/**
 * The network failures left once the shared layer has mapped the contact routes' own
 * refusals (not found, already pending, rate limited).
 */
private fun NetworkError.toAddContactError(isOnline: Boolean): AddContactError = when (this) {
    NetworkError.NO_INTERNET,
    NetworkError.REQUEST_TIMEOUT,
        -> AddContactError.NO_INTERNET

    NetworkError.UNREACHABLE ->
        if (isOnline) AddContactError.SERVER_UNREACHABLE else AddContactError.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.UNKNOWN,
        -> AddContactError.UNKNOWN
}
