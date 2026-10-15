package expense

import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOW = 1_760_400_000_000

class OneTimeExpenseSettledMarkTimeTest {

    @Test
    fun `the first mark is made now`() {
        assertEquals(NOW, nextSettledMarkTime(now = NOW, newest = null))
    }

    @Test
    fun `a mark is made now when now is after the newest shown`() {
        assertEquals(NOW, nextSettledMarkTime(now = NOW, newest = NOW - 60_000))
    }

    /** A clock that ran ahead once, then was put right, or one behind the creditor's last session. */
    @Test
    fun `a mark follows the newest shown when the clock is not past it`() {
        assertEquals(NOW + 3_600_001, nextSettledMarkTime(now = NOW, newest = NOW + 3_600_000))
        assertEquals(NOW + 1, nextSettledMarkTime(now = NOW, newest = NOW))
    }
}
