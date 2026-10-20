package model

/**
 * Where the user stands with checking a contact's identity, at a granularity the UI can show.
 * The composeApp callback impl maps the shared state onto this.
 */
sealed interface ContactVerification {

    /**
     * The safety number the user compares with the contact, twelve groups of five digits
     * separated by spaces, and whether it is the one they marked verified.
     */
    data class Available(val safetyNumber: String, val isVerified: Boolean) : ContactVerification

    /**
     * The contact presented a security key other than the one recorded for them, which is either
     * a new identity or an impersonation attempt, so there is no safety number to compare.
     */
    data object IdentityChanged : ContactVerification

    /** They are not, or no longer, one of the user's contacts. */
    data object NotContact : ContactVerification

    /** The safety number could not be computed. */
    data class Unavailable(val error: ContactVerificationError) : ContactVerification
}
