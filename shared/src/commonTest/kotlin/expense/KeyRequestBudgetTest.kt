package expense

import util.spendMessageBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val MINUTE = 60L * 1000
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Some moment, far enough from zero that nothing below can go negative. */
private const val T = 1_800_000_000_000L

/** The budget a creditor is asked for keys out of, see `OneTimeExpenseSync.requestMissingKeys`. */
class KeyRequestBudgetTest {

    private fun ask(budgetFullAt: Long?, now: Long) =
        spendMessageBudget(budgetFullAt = budgetFullAt, now = now, onlyWhenWhole = false)

    /** Back from a log-out: a request names a hundred keys, and the ones past it stay due for the next sync. */
    @Test
    fun `two requests go out at once and a third not before an hour after the first`() {
        val first = assertNotNull(ask(budgetFullAt = null, now = T))
        val second = assertNotNull(ask(budgetFullAt = first, now = T + MINUTE))

        assertNull(ask(budgetFullAt = second, now = T + 2 * MINUTE))
        assertNotNull(ask(budgetFullAt = second, now = T + HOUR))
    }

    /** What the asker's own backoff would try at thirty minutes, the creditor's budget would not answer. */
    @Test
    fun `a try the creditor would not answer waits until it would`() {
        val first = assertNotNull(ask(budgetFullAt = null, now = T))
        val second = assertNotNull(ask(budgetFullAt = first, now = T + 10 * MINUTE))

        assertNull(ask(budgetFullAt = second, now = T + 30 * MINUTE))
        assertNotNull(ask(budgetFullAt = second, now = T + HOUR))
    }

    /**
     * A creditor who always has an expense the user cannot open, fresh or past the hundred a
     * request names, and wakes this device for a sync every minute, for three days.
     */
    @Test
    fun `no creditor is asked more than 25 times in a day`() {
        var budgetFullAt: Long? = null
        val asked = mutableListOf<Long>()

        var now = T
        while (now < T + 3 * DAY) {
            ask(budgetFullAt = budgetFullAt, now = now)?.let {
                budgetFullAt = it
                asked += now
            }

            now += MINUTE
        }

        val most = asked.maxOf { start -> asked.count { it in start until start + DAY } }

        assertEquals(25, most)
    }
}
