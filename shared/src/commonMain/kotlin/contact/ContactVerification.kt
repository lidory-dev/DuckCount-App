package contact

/** Where the user stands with checking a contact's identity, see [SafetyNumbers]. */
sealed interface ContactVerification {

    /**
     * The safety number the user compares with the contact, twelve groups of five digits
     * separated by spaces, and whether it is the one they marked verified.
     */
    data class Available(val safetyNumber: String, val isVerified: Boolean) : ContactVerification

    /**
     * This device and the vault pin different identities for the contact, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity], so there is no
     * safety number to show: the identity in doubt is the very thing it would vouch for, and
     * whatever the user marked verified before no longer counts.
     */
    data object IdentityChanged : ContactVerification

    /** They are not, or no longer, one of the user's contacts. */
    data object NotContact : ContactVerification

    /** The safety number could not be computed. */
    data class Unavailable(val error: ContactVerificationError) : ContactVerification
}
