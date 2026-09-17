package contact

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * Why a contact could not be removed.
 *
 * Every case is about telling the contact, which comes first: nothing is removed until
 * they have been told, so the user can simply try again.
 */
sealed interface RemoveContactError : Error {

    /** The user's own MLS client could not be opened to encrypt the notice. */
    data class Identity(val error: MlsIdentityError) : RemoveContactError

    /** The user's own MLS storage failed while encrypting the notice. */
    data object Storage : RemoveContactError

    /** Too many direct messages sent in a short amount of time. */
    data object RateLimited : RemoveContactError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : RemoveContactError
}
