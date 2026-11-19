package contact

import app.lidory.duckcount.UsernameError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError

// What the iOS contact screens tell apart, as Android's `model` enums of `:feature:contactList` do,
// which live in an Android-only module, and the mappings to them, as `ContactMappers.kt` has them.
// What went wrong with the user's own key material, in the vault or in the MLS client, is folded
// into `UNKNOWN`: nothing the user can act on. Network failures along the way are told as such,
// whichever call they came from, and a connection that failed is told apart from airplane mode
// with `isOnline`: Ktor's Darwin engine reports both as a connection that failed.

/** Why a contact request could not be sent. */
enum class AddContactFailure {
    /** The username is not one anybody could have reserved. */
    INVALID_USERNAME,

    /** Nobody reserved that username, or its holder cannot be reached yet: the server answers both alike. */
    NOT_FOUND,

    /**
     * The username is the user's own. Not `SELF`, which Objective-C would get as a class property
     * `self` overriding NSObject's: the class then initializes itself forever, and the app dies the
     * first time any of these failures reaches Swift.
     */
    OWN_USERNAME,

    /** The username belongs to one of the user's contacts already. */
    ALREADY_CONTACT,

    /** A request to this user is already waiting for their answer. */
    ALREADY_PENDING,

    /** Too many lookups or requests in too short a time. */
    RATE_LIMITED,

    /** The recipient presented a security key other than the one this device saw for them before. */
    IDENTITY_CHANGED,

    /** The user blocked this user, and has to unblock them first. */
    BLOCKED,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,
    UNKNOWN,
}

/** Why a contact request could not be accepted, refused, or its sender blocked. */
enum class AnswerContactRequestFailure {
    /** The request is no longer waiting for the user. */
    NOT_FOUND,

    /** The request could not be refused: the user had accepted it already, so its sender was added as a contact. */
    ALREADY_ACCEPTED,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,

    /** The sender presented a security key other than the one this device has seen for them since the request arrived. */
    IDENTITY_CHANGED,

    UNKNOWN,
}

/** Why a contact could not be removed, or blocked, which removes them first. */
enum class RemoveContactFailure {
    /** Too many messages sent in too short a time. */
    RATE_LIMITED,

    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,
    UNKNOWN,
}

/** Why the safety number of a contact could not be computed. */
enum class ContactVerificationFailure {
    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,
    UNKNOWN,
}

/** Why a username cannot be looked up or reserved as typed; nothing typed yet is no mistake to point out. */
enum class UsernameInputFailure {
    MUST_START_WITH_LETTER,
    INVALID_CHARACTER,
    TOO_LONG,
}

/** Where the user stands with checking that a contact is who this device talks to, as Android's `ContactVerification`. */
sealed interface ContactVerificationStatus {

    /** The safety number they compare, twelve groups of five digits separated by spaces, and whether it is the one marked verified. */
    data class Available(val safetyNumber: String, val isVerified: Boolean) : ContactVerificationStatus

    /** The contact presented a security key other than the one recorded for them: there is no number to compare. */
    data object IdentityChanged : ContactVerificationStatus

    /** They are not, or no longer, one of the user's contacts. */
    data object NotContact : ContactVerificationStatus

    /** The safety number could not be computed. */
    data class Unavailable(val failure: ContactVerificationFailure) : ContactVerificationStatus
}

/** An empty username is no mistake to point out. */
internal fun UsernameError.toInputFailure(): UsernameInputFailure? = when (this) {
    UsernameError.EMPTY -> null
    UsernameError.MUST_START_WITH_LETTER -> UsernameInputFailure.MUST_START_WITH_LETTER
    UsernameError.INVALID_CHARACTER -> UsernameInputFailure.INVALID_CHARACTER
    UsernameError.TOO_LONG -> UsernameInputFailure.TOO_LONG
}

internal fun SendContactRequestError.toAddContactFailure(isOnline: Boolean): AddContactFailure = when (this) {
    SendContactRequestError.InvalidUsername -> AddContactFailure.INVALID_USERNAME
    SendContactRequestError.NotFound -> AddContactFailure.NOT_FOUND
    SendContactRequestError.Self -> AddContactFailure.OWN_USERNAME
    SendContactRequestError.AlreadyContact -> AddContactFailure.ALREADY_CONTACT
    SendContactRequestError.AlreadyPending -> AddContactFailure.ALREADY_PENDING
    SendContactRequestError.RateLimited -> AddContactFailure.RATE_LIMITED
    SendContactRequestError.IdentityChanged -> AddContactFailure.IDENTITY_CHANGED
    SendContactRequestError.Blocked -> AddContactFailure.BLOCKED
    is SendContactRequestError.Network -> error.toAddContactFailure(isOnline = isOnline)
    is SendContactRequestError.Vault -> error.toAddContactFailure(isOnline = isOnline)
    is SendContactRequestError.Identity -> error.toAddContactFailure(isOnline = isOnline)
    is SendContactRequestError.Contacts -> error.toAddContactFailure(isOnline = isOnline)
    SendContactRequestError.Crypto -> AddContactFailure.UNKNOWN

    // Only a request to a group member is refused so, which a username never is.
    SendContactRequestError.NotInGroup -> AddContactFailure.UNKNOWN
}

/** Restoring the contacts fails on the vault or on the MLS client, told as above. */
private fun ContactSyncError.toAddContactFailure(isOnline: Boolean): AddContactFailure = when (this) {
    is ContactSyncError.Vault -> error.toAddContactFailure(isOnline = isOnline)
    is ContactSyncError.Identity -> error.toAddContactFailure(isOnline = isOnline)

    ContactSyncError.Storage,
    is ContactSyncError.Profile,
    is ContactSyncError.ProfileKey,
    is ContactSyncError.Network,
        -> AddContactFailure.UNKNOWN
}

private fun MlsIdentityError.toAddContactFailure(isOnline: Boolean): AddContactFailure = when (this) {
    is MlsIdentityError.Vault -> error.toAddContactFailure(isOnline = isOnline)

    MlsIdentityError.MissingSession,
    MlsIdentityError.Crypto,
        -> AddContactFailure.UNKNOWN
}

private fun VaultError.toAddContactFailure(isOnline: Boolean): AddContactFailure = when (this) {
    is VaultError.Network -> error.toAddContactFailure(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.MalformedBlockList,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> AddContactFailure.UNKNOWN
}

/** The network failures left once the shared layer has mapped the contact routes' own refusals. */
private fun NetworkError.toAddContactFailure(isOnline: Boolean): AddContactFailure = when (this) {
    NetworkError.NO_INTERNET -> AddContactFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> AddContactFailure.TIMEOUT
    NetworkError.UNREACHABLE -> if (isOnline) AddContactFailure.SERVER_UNREACHABLE else AddContactFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> AddContactFailure.UNKNOWN
}

internal fun AnswerContactRequestError.toAnswerFailure(isOnline: Boolean): AnswerContactRequestFailure = when (this) {
    AnswerContactRequestError.NotFound -> AnswerContactRequestFailure.NOT_FOUND
    AnswerContactRequestError.AlreadyAccepted -> AnswerContactRequestFailure.ALREADY_ACCEPTED
    is AnswerContactRequestError.Network -> error.toAnswerFailure(isOnline = isOnline)
    is AnswerContactRequestError.Vault -> error.toAnswerFailure(isOnline = isOnline)
    is AnswerContactRequestError.Identity -> error.toAnswerFailure(isOnline = isOnline)
    is AnswerContactRequestError.Contacts -> error.toAnswerFailure(isOnline = isOnline)
    AnswerContactRequestError.Crypto -> AnswerContactRequestFailure.UNKNOWN
    AnswerContactRequestError.IdentityChanged -> AnswerContactRequestFailure.IDENTITY_CHANGED
}

private fun ContactSyncError.toAnswerFailure(isOnline: Boolean): AnswerContactRequestFailure = when (this) {
    is ContactSyncError.Vault -> error.toAnswerFailure(isOnline = isOnline)
    is ContactSyncError.Identity -> error.toAnswerFailure(isOnline = isOnline)

    ContactSyncError.Storage,
    is ContactSyncError.Profile,
    is ContactSyncError.ProfileKey,
    is ContactSyncError.Network,
        -> AnswerContactRequestFailure.UNKNOWN
}

private fun MlsIdentityError.toAnswerFailure(isOnline: Boolean): AnswerContactRequestFailure = when (this) {
    is MlsIdentityError.Vault -> error.toAnswerFailure(isOnline = isOnline)

    MlsIdentityError.MissingSession,
    MlsIdentityError.Crypto,
        -> AnswerContactRequestFailure.UNKNOWN
}

private fun VaultError.toAnswerFailure(isOnline: Boolean): AnswerContactRequestFailure = when (this) {
    is VaultError.Network -> error.toAnswerFailure(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.MalformedBlockList,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> AnswerContactRequestFailure.UNKNOWN
}

/** The network failures left once the shared layer has mapped the request being gone. */
private fun NetworkError.toAnswerFailure(isOnline: Boolean): AnswerContactRequestFailure = when (this) {
    NetworkError.NO_INTERNET -> AnswerContactRequestFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> AnswerContactRequestFailure.TIMEOUT
    NetworkError.UNREACHABLE ->
        if (isOnline) AnswerContactRequestFailure.SERVER_UNREACHABLE else AnswerContactRequestFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> AnswerContactRequestFailure.UNKNOWN
}

internal fun RemoveContactError.toRemoveFailure(isOnline: Boolean): RemoveContactFailure = when (this) {
    is RemoveContactError.RateLimited -> RemoveContactFailure.RATE_LIMITED
    is RemoveContactError.Network -> error.toRemoveFailure(isOnline = isOnline)

    is RemoveContactError.Identity -> when (val identityError = error) {
        is MlsIdentityError.Vault -> identityError.error.toRemoveFailure(isOnline = isOnline)

        MlsIdentityError.MissingSession,
        MlsIdentityError.Crypto,
            -> RemoveContactFailure.UNKNOWN
    }

    RemoveContactError.DeviceLocked,
    RemoveContactError.Storage,
        -> RemoveContactFailure.UNKNOWN
}

private fun VaultError.toRemoveFailure(isOnline: Boolean): RemoveContactFailure = when (this) {
    is VaultError.Network -> error.toRemoveFailure(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.MalformedBlockList,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> RemoveContactFailure.UNKNOWN
}

/** The network failures left once the shared layer has mapped the message route's own outcomes. */
private fun NetworkError.toRemoveFailure(isOnline: Boolean): RemoveContactFailure = when (this) {
    NetworkError.NO_INTERNET -> RemoveContactFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> RemoveContactFailure.TIMEOUT
    NetworkError.UNREACHABLE -> if (isOnline) RemoveContactFailure.SERVER_UNREACHABLE else RemoveContactFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> RemoveContactFailure.UNKNOWN
}

/** Why the safety number could not be computed: opening the MLS client the first time can meet the network, reading the vault. */
internal fun ContactVerification.toStatus(isOnline: Boolean): ContactVerificationStatus = when (this) {
    is ContactVerification.Available ->
        ContactVerificationStatus.Available(safetyNumber = safetyNumber, isVerified = isVerified)

    ContactVerification.IdentityChanged -> ContactVerificationStatus.IdentityChanged

    ContactVerification.NotContact -> ContactVerificationStatus.NotContact

    is ContactVerification.Unavailable ->
        ContactVerificationStatus.Unavailable(failure = error.toVerificationFailure(isOnline = isOnline))
}

private fun ContactVerificationError.toVerificationFailure(isOnline: Boolean): ContactVerificationFailure =
    when (this) {
        is ContactVerificationError.Identity -> when (val identityError = error) {
            is MlsIdentityError.Vault -> identityError.error.toVerificationFailure(isOnline = isOnline)

            MlsIdentityError.MissingSession,
            MlsIdentityError.Crypto,
                -> ContactVerificationFailure.UNKNOWN
        }

        ContactVerificationError.Storage -> ContactVerificationFailure.UNKNOWN
    }

private fun VaultError.toVerificationFailure(isOnline: Boolean): ContactVerificationFailure = when (this) {
    is VaultError.Network -> error.toVerificationFailure(isOnline = isOnline)

    VaultError.MissingRootKey,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.MalformedBlockList,
    VaultError.OrphanedEntry,
    VaultError.Contended,
        -> ContactVerificationFailure.UNKNOWN
}

private fun NetworkError.toVerificationFailure(isOnline: Boolean): ContactVerificationFailure = when (this) {
    NetworkError.NO_INTERNET -> ContactVerificationFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ContactVerificationFailure.TIMEOUT
    NetworkError.UNREACHABLE ->
        if (isOnline) ContactVerificationFailure.SERVER_UNREACHABLE else ContactVerificationFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNAUTHORIZED,
    NetworkError.NOT_FOUND,
    NetworkError.CONFLICT,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.UNPROCESSABLE_ENTITY,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> ContactVerificationFailure.UNKNOWN
}
