package expense

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The timestamps the iOS expense form dates an expense with, see [currentTimestamp], which every
 * device refuses to open unless they are exactly what Kotlin's formatter writes.
 */
class CurrentTimestampTest {

    @Test
    fun aTimestampWrittenNowIsOneEveryDeviceOpens() {
        repeat(times = 50) {
            assertTrue(currentTimestamp().isCanonicalTimestamp())
        }
    }

    /** What iOS wrote before: three digits whatever the milliseconds. */
    @Test
    fun aFractionWithTrailingZerosIsRefused() {
        assertFalse("2026-11-13T14:05:30.120".isCanonicalTimestamp())
        assertFalse("2026-11-13T14:05:30.500".isCanonicalTimestamp())
        assertFalse("2026-11-13T14:05:30.000".isCanonicalTimestamp())
    }

    @Test
    fun aFractionWithoutTrailingZerosIsTaken() {
        assertTrue("2026-11-13T14:05:30.12".isCanonicalTimestamp())
        assertTrue("2026-11-13T14:05:30.5".isCanonicalTimestamp())
        assertTrue("2026-11-13T14:05:30".isCanonicalTimestamp())
        assertTrue("2026-11-13T14:05:30.123".isCanonicalTimestamp())
    }
}
