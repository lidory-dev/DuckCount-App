package app.lidory.duckcount

/**
 * The longest each field of a profile may be, counted as every text limit of the app is: in the
 * UTF-16 units of a Kotlin `String.length`, which a Swift string reaching the shared module is
 * counted in too.
 *
 * The server holds each field to the longest ciphertext a value this long can seal to, so the
 * three are mirrored in the DuckCount API and change with it. A field is sealed as its UTF-8
 * bytes, at most three per UTF-16 unit, a pair of surrogates taking four for its two, under
 * AES-256-GCM, which adds its 16-byte tag and no prefix, the nonce travelling apart, and the
 * ciphertext goes up in padded Base64: `4 * ceil((3 * length + 16) / 3)` characters at most.
 * That is 224 for [MAX_FULL_NAME_LENGTH], 152 for [MAX_PHONE_NUMBER_LENGTH] and 192 for
 * [MAX_BANK_DETAILS_LENGTH].
 */
object ProfileLimits {
    const val MAX_FULL_NAME_LENGTH = 50

    /**
     * The app has no screen for a phone number yet and seals an empty one, so nothing checks
     * this on the device: it bounds what the server takes.
     */
    const val MAX_PHONE_NUMBER_LENGTH = 32

    /**
     * The app has no screen for bank details yet and seals empty ones, so nothing checks this on
     * the device: it bounds what the server takes.
     */
    const val MAX_BANK_DETAILS_LENGTH = 42
}
