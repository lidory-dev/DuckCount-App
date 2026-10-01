package contact

import mls.MlsIdentityError
import util.Error
import util.NetworkError
import vault.VaultError

/** Why a contact request could not be sent. */
sealed interface SendContactRequestError : Error {

    /** The username is not one anybody could have reserved. */
    data object InvalidUsername : SendContactRequestError

    /**
     * Nobody reserved that username, or its holder has no key package to be reached with.
     * The server answers both the same way.
     */
    data object NotFound : SendContactRequestError

    /** The username is the user's own. */
    data object Self : SendContactRequestError

    /** The username belongs to somebody who already is one of the user's contacts. */
    data object AlreadyContact : SendContactRequestError

    /** A request from the user to this recipient is already waiting for an answer. */
    data object AlreadyPending : SendContactRequestError

    /** Too many lookups or requests in a short amount of time. */
    data object RateLimited : SendContactRequestError

    /**
     * The recipient's key package carries a signature key other than the one this device
     * pinned for them, or the vault pins yet another one for them, which blocks them, see
     * [database.data.local.entity.IdentityConflictEntity]. Either they legitimately got a
     * new identity, or somebody is impersonating them, and nothing here can tell which.
     */
    data object IdentityChanged : SendContactRequestError

    /**
     * The user's contacts could not be brought back from the vault. Until they are, this
     * device can tell neither whether the recipient is one of them, nor which identity is
     * theirs.
     */
    data class Contacts(val error: ContactSyncError) : SendContactRequestError

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : SendContactRequestError

    /** The profile encryption key to hand over could not be read from the vault. */
    data class Vault(val error: VaultError) : SendContactRequestError

    /** The library could not build the request, for instance from an unusable key package. */
    data object Crypto : SendContactRequestError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : SendContactRequestError
}
