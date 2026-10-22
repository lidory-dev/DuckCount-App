package app.lidory.duckcount.contactList

import contact.ContactSyncError
import contact.SendContactRequestError
import mls.MlsIdentityError
import model.AddContactError
import model.AnswerContactRequestError
import model.ContactVerification
import model.ContactVerificationError
import model.RemoveContactError
import model.User
import model.UsernameInputError
import util.NetworkError
import vault.VaultError
import app.lidory.duckcount.UsernameError as SharedUsernameError
import contact.AnswerContactRequestError as SharedAnswerContactRequestError
import contact.ContactVerification as SharedContactVerification
import contact.ContactVerificationError as SharedContactVerificationError
import contact.RemoveContactError as SharedRemoveContactError
import database.domain.User as UserDB

fun User.toDb(): UserDB = UserDB(
    uuid = uuid,
    username = username,
    fullName = fullName,
)

fun SharedUsernameError.toUsernameInputError(): UsernameInputError = when (this) {
    SharedUsernameError.EMPTY -> UsernameInputError.EMPTY
    SharedUsernameError.MUST_START_WITH_LETTER -> UsernameInputError.MUST_START_WITH_LETTER
    SharedUsernameError.INVALID_CHARACTER -> UsernameInputError.INVALID_CHARACTER
    SharedUsernameError.TOO_LONG -> UsernameInputError.TOO_LONG
}

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
    SendContactRequestError.AlreadyContact -> AddContactError.ALREADY_CONTACT
    SendContactRequestError.AlreadyPending -> AddContactError.ALREADY_PENDING
    SendContactRequestError.RateLimited -> AddContactError.RATE_LIMITED
    SendContactRequestError.IdentityChanged -> AddContactError.IDENTITY_CHANGED

    is SendContactRequestError.Network -> error.toAddContactError(isOnline = isOnline)

    is SendContactRequestError.Vault -> error.toAddContactError(isOnline = isOnline)

    is SendContactRequestError.Identity -> error.toAddContactError(isOnline = isOnline)

    is SendContactRequestError.Contacts -> error.toAddContactError(isOnline = isOnline)

    SendContactRequestError.Crypto -> AddContactError.UNKNOWN
}

/** Restoring the contacts fails on the vault or on the MLS client, reported as above. */
private fun ContactSyncError.toAddContactError(isOnline: Boolean): AddContactError = when (this) {
    is ContactSyncError.Vault -> error.toAddContactError(isOnline = isOnline)

    is ContactSyncError.Identity -> error.toAddContactError(isOnline = isOnline)

    ContactSyncError.Storage,
    is ContactSyncError.Profile,
    is ContactSyncError.ProfileKey,
    is ContactSyncError.Network,
        -> AddContactError.UNKNOWN
}

private fun MlsIdentityError.toAddContactError(isOnline: Boolean): AddContactError = when (this) {
    is MlsIdentityError.Vault -> error.toAddContactError(isOnline = isOnline)

    MlsIdentityError.MissingSession,
    MlsIdentityError.Crypto,
        -> AddContactError.UNKNOWN
}

private fun VaultError.toAddContactError(isOnline: Boolean): AddContactError = when (this) {
    is VaultError.Network -> error.toAddContactError(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> AddContactError.UNKNOWN
}

/**
 * Maps a failed answer to a contact request to the reason the UI surfaces.
 *
 * Same folding as [toAddContactError]: the user's own key material failing is
 * [AnswerContactRequestError.UNKNOWN], network failures are reported as such.
 */
fun SharedAnswerContactRequestError.toAnswerContactRequestError(
    isOnline: Boolean,
): AnswerContactRequestError = when (this) {
    SharedAnswerContactRequestError.NotFound -> AnswerContactRequestError.NOT_FOUND

    SharedAnswerContactRequestError.AlreadyAccepted -> AnswerContactRequestError.ALREADY_ACCEPTED

    is SharedAnswerContactRequestError.Network -> error.toAnswerContactRequestError(isOnline = isOnline)

    is SharedAnswerContactRequestError.Vault -> error.toAnswerContactRequestError(isOnline = isOnline)

    is SharedAnswerContactRequestError.Identity -> error.toAnswerContactRequestError(isOnline = isOnline)

    is SharedAnswerContactRequestError.Contacts -> error.toAnswerContactRequestError(isOnline = isOnline)

    SharedAnswerContactRequestError.Crypto -> AnswerContactRequestError.UNKNOWN

    SharedAnswerContactRequestError.IdentityChanged -> AnswerContactRequestError.IDENTITY_CHANGED
}

/** Same folding as for a contact request, see [toAddContactError]. */
private fun ContactSyncError.toAnswerContactRequestError(isOnline: Boolean): AnswerContactRequestError =
    when (this) {
        is ContactSyncError.Vault -> error.toAnswerContactRequestError(isOnline = isOnline)

        is ContactSyncError.Identity -> error.toAnswerContactRequestError(isOnline = isOnline)

        ContactSyncError.Storage,
        is ContactSyncError.Profile,
        is ContactSyncError.ProfileKey,
        is ContactSyncError.Network,
            -> AnswerContactRequestError.UNKNOWN
    }

private fun MlsIdentityError.toAnswerContactRequestError(isOnline: Boolean): AnswerContactRequestError =
    when (this) {
        is MlsIdentityError.Vault -> error.toAnswerContactRequestError(isOnline = isOnline)

        MlsIdentityError.MissingSession,
        MlsIdentityError.Crypto,
            -> AnswerContactRequestError.UNKNOWN
    }

private fun VaultError.toAnswerContactRequestError(isOnline: Boolean): AnswerContactRequestError =
    when (this) {
        is VaultError.Network -> error.toAnswerContactRequestError(isOnline = isOnline)

        VaultError.MissingRootKey,
        VaultError.Crypto,
        VaultError.MalformedKeyRing,
        VaultError.MalformedContactBook,
        VaultError.OrphanedEntry,
        VaultError.Contended,
            -> AnswerContactRequestError.UNKNOWN
    }

/**
 * Maps a failed contact removal to the reason the UI surfaces.
 *
 * Same folding as [toAddContactError]: the user's own key material failing is
 * [RemoveContactError.UNKNOWN], network failures are reported as such.
 */
fun SharedRemoveContactError.toRemoveContactError(isOnline: Boolean): RemoveContactError = when (this) {
    is SharedRemoveContactError.RateLimited -> RemoveContactError.RATE_LIMITED

    is SharedRemoveContactError.Network -> error.toRemoveContactError(isOnline = isOnline)

    is SharedRemoveContactError.Identity -> when (val identityError = error) {
        is MlsIdentityError.Vault -> identityError.error.toRemoveContactError(isOnline = isOnline)

        MlsIdentityError.MissingSession,
        MlsIdentityError.Crypto,
            -> RemoveContactError.UNKNOWN
    }

    SharedRemoveContactError.DeviceLocked,
    SharedRemoveContactError.Storage,
        -> RemoveContactError.UNKNOWN
}

private fun VaultError.toRemoveContactError(isOnline: Boolean): RemoveContactError = when (this) {
    is VaultError.Network -> error.toRemoveContactError(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> RemoveContactError.UNKNOWN
}

/**
 * Maps where the user stands with checking a contact to what the UI shows.
 *
 * Same folding as [toAddContactError] for why the safety number could not be computed: the
 * user's own key material failing is [ContactVerificationError.UNKNOWN], network failures, which
 * opening the MLS client for the first time can meet reading the vault, are reported as such.
 */
fun SharedContactVerification.toContactVerification(isOnline: Boolean): ContactVerification = when (this) {
    is SharedContactVerification.Available ->
        ContactVerification.Available(safetyNumber = safetyNumber, isVerified = isVerified)

    SharedContactVerification.IdentityChanged -> ContactVerification.IdentityChanged

    SharedContactVerification.NotContact -> ContactVerification.NotContact

    is SharedContactVerification.Unavailable ->
        ContactVerification.Unavailable(error = error.toContactVerificationError(isOnline = isOnline))
}

private fun SharedContactVerificationError.toContactVerificationError(isOnline: Boolean): ContactVerificationError =
    when (this) {
        is SharedContactVerificationError.Identity -> when (val identityError = error) {
            is MlsIdentityError.Vault -> identityError.error.toContactVerificationError(isOnline = isOnline)

            MlsIdentityError.MissingSession,
            MlsIdentityError.Crypto,
                -> ContactVerificationError.UNKNOWN
        }

        SharedContactVerificationError.Storage -> ContactVerificationError.UNKNOWN
    }

private fun VaultError.toContactVerificationError(isOnline: Boolean): ContactVerificationError = when (this) {
    is VaultError.Network -> error.toContactVerificationError(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> ContactVerificationError.UNKNOWN
}

/** The network failures reading the vault can end on. */
private fun NetworkError.toContactVerificationError(isOnline: Boolean): ContactVerificationError = when (this) {
    NetworkError.NO_INTERNET -> ContactVerificationError.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ContactVerificationError.TIMEOUT

    NetworkError.UNREACHABLE ->
        if (isOnline) ContactVerificationError.SERVER_UNREACHABLE else ContactVerificationError.NO_INTERNET

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
        -> ContactVerificationError.UNKNOWN
}

/**
 * The network failures left once the shared layer has mapped the message route's own
 * outcomes (a recipient that is gone, rate limited).
 */
private fun NetworkError.toRemoveContactError(isOnline: Boolean): RemoveContactError = when (this) {
    NetworkError.NO_INTERNET -> RemoveContactError.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> RemoveContactError.TIMEOUT

    NetworkError.UNREACHABLE ->
        if (isOnline) RemoveContactError.SERVER_UNREACHABLE else RemoveContactError.NO_INTERNET

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
        -> RemoveContactError.UNKNOWN
}

/** The network failures left once the shared layer has mapped the request being gone. */
private fun NetworkError.toAnswerContactRequestError(isOnline: Boolean): AnswerContactRequestError =
    when (this) {
        NetworkError.NO_INTERNET -> AnswerContactRequestError.NO_INTERNET
        NetworkError.REQUEST_TIMEOUT -> AnswerContactRequestError.TIMEOUT

        NetworkError.UNREACHABLE ->
            if (isOnline) AnswerContactRequestError.SERVER_UNREACHABLE else AnswerContactRequestError.NO_INTERNET

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
            -> AnswerContactRequestError.UNKNOWN
    }

/**
 * The network failures left once the shared layer has mapped the contact routes' own
 * refusals (not found, already pending, rate limited).
 */
private fun NetworkError.toAddContactError(isOnline: Boolean): AddContactError = when (this) {
    NetworkError.NO_INTERNET -> AddContactError.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> AddContactError.TIMEOUT

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
