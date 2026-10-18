package contact

import mls.MlsIdentityError
import util.Error
import util.NetworkError

/**
 * Why a contact could not be removed.
 *
 * Every case comes before the contact is told, or is about telling them, which comes first:
 * nothing is removed until they have been told, so the user can simply try again.
 */
sealed interface RemoveContactError : Error {

    /**
     * The device locked before the new key the removal owes the user's profile could be kept on
     * it, the removal having waited for a rotation of the key, an edit of the profile or another
     * removal under way, see
     * [ProfileRepository.oweProfileKeyRotation][profile.ProfileRepository.oweProfileKeyRotation].
     */
    data object DeviceLocked : RemoveContactError

    /** The user's own MLS client could not be opened to encrypt the notice. */
    data class Identity(val error: MlsIdentityError) : RemoveContactError

    /** The user's own MLS storage failed while encrypting the notice. */
    data object Storage : RemoveContactError

    /** Too many direct messages sent in a short amount of time. */
    data object RateLimited : RemoveContactError

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : RemoveContactError
}
