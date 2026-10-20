package contact

import mls.MlsIdentityError
import util.Error

/**
 * Why the safety number of a contact could not be computed, see [SafetyNumbers].
 *
 * Nothing here is final: the next attempt computes it again.
 */
sealed interface ContactVerificationError : Error {

    /** The user's own MLS client could not be opened. */
    data class Identity(val error: MlsIdentityError) : ContactVerificationError

    /** The user's own MLS storage failed reading the identity pinned for the contact. */
    data object Storage : ContactVerificationError
}
