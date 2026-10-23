package networking

import kotlin.test.Test
import kotlin.test.assertEquals

/** The SHA-1 "password" is known by, `06` and `0B` among its bytes. */
private const val PASSWORD_SHA1 = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"

/** An answer of the range API for the first five characters of [PASSWORD_SHA1]. */
private val RANGE = listOf(
    "003D68EB55068C33ACE09247EE4C639306B:3",
    "1E4C9B93F3F0682250B6CF8331B7EE68FD8:10434004",
    "1E7AC6E1A6D3A8D3E3C0A2A0E8C0A5A4C11:1",
).joinToString(separator = "\r\n")

/**
 * How a password is looked up in the breaches Have I Been Pwned knows of, see
 * [HaveIBeenPwnedClient.passwordLeakCount].
 */
class PasswordLeakCountTest {

    /**
     * Spelled as the range API spells it, two digits a byte: a single one for a byte under `0x10`
     * would shift the rest, and the range asked for and the line looked up in it would be another
     * password's.
     */
    @Test
    fun `a password is looked up by its SHA-1 in uppercase hexadecimal`() {
        assertEquals(PASSWORD_SHA1, sha1("password".encodeToByteArray()).toUppercaseHex())
    }

    @Test
    fun `a breached password is counted from its line of the range`() {
        assertEquals(10434004, leakCount(range = RANGE, hashSuffix = PASSWORD_SHA1.drop(5)))
    }

    @Test
    fun `a password the range does not list was never breached`() {
        assertEquals(0, leakCount(range = RANGE, hashSuffix = "0000000000000000000000000000000000A"))
    }
}
