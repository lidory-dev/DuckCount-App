package channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val MINUTE = 60L * 1000
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Some moment, far enough from zero that nothing below can go negative. */
private const val T = 1_800_000_000_000L

class ReplyBudgetTest {

    @Test
    fun `a whole budget lets either reply out`() {
        assertEquals(T + HOUR, spendReply(budgetFullAt = null, now = T, reply = Reply.EXPENSE_KEYS))
        assertEquals(T + HOUR, spendReply(budgetFullAt = null, now = T, reply = Reply.CHANNEL_JOINED))
    }

    @Test
    fun `two answers go out at once and the third waits an hour`() {
        val first = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.EXPENSE_KEYS))
        val second = assertNotNull(spendReply(budgetFullAt = first, now = T, reply = Reply.EXPENSE_KEYS))

        assertNull(spendReply(budgetFullAt = second, now = T, reply = Reply.EXPENSE_KEYS))
        assertNull(spendReply(budgetFullAt = second, now = T + HOUR - 1, reply = Reply.EXPENSE_KEYS))
        assertNotNull(spendReply(budgetFullAt = second, now = T + HOUR, reply = Reply.EXPENSE_KEYS))
    }

    /** However many requests a peer sends, as many as their own allowance buys. */
    @Test
    fun `two replies is all a peer gets at one moment`() {
        var budgetFullAt: Long? = null
        var answered = 0

        repeat(500) {
            spendReply(budgetFullAt = budgetFullAt, now = T, reply = Reply.EXPENSE_KEYS)?.let {
                budgetFullAt = it
                answered++
            }
        }

        assertEquals(2, answered)
    }

    /**
     * The asker asks again ten minutes after their first try, and then not before an hour
     * after it, see `backoffFor` and `OneTimeExpenseSync.requestMissingKeys`.
     */
    @Test
    fun `an answer lost on the way is answered at the asker's next try`() {
        val lost = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.EXPENSE_KEYS))

        assertNotNull(spendReply(budgetFullAt = lost, now = T + 10 * MINUTE, reply = Reply.EXPENSE_KEYS))
    }

    @Test
    fun `a second answer lost straight after is answered at the asker's try an hour after the first`() {
        val first = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.EXPENSE_KEYS))
        val second = assertNotNull(spendReply(budgetFullAt = first, now = T + 10 * MINUTE, reply = Reply.EXPENSE_KEYS))

        assertNull(spendReply(budgetFullAt = second, now = T + 30 * MINUTE, reply = Reply.EXPENSE_KEYS))
        assertNotNull(spendReply(budgetFullAt = second, now = T + HOUR, reply = Reply.EXPENSE_KEYS))
    }

    /** Back from a log-out, the asker rebuilt the channel to ask, and the answer goes in the group just joined. */
    @Test
    fun `a channel_joined waits until no answer is outstanding`() {
        val answered = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.EXPENSE_KEYS))

        assertNull(spendReply(budgetFullAt = answered, now = T, reply = Reply.CHANNEL_JOINED))
        assertNull(spendReply(budgetFullAt = answered, now = T + HOUR - 1, reply = Reply.CHANNEL_JOINED))
        assertNotNull(spendReply(budgetFullAt = answered, now = T + HOUR, reply = Reply.CHANNEL_JOINED))
    }

    /** The Welcome can be read a pass before the request it came ahead of. */
    @Test
    fun `an answer still goes out right after a channel_joined`() {
        val told = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.CHANNEL_JOINED))

        assertNotNull(spendReply(budgetFullAt = told, now = T + MINUTE, reply = Reply.EXPENSE_KEYS))
    }

    /** The Welcome read a pass ahead of the request, and the answer to the request lost. */
    @Test
    fun `keys go out an hour after the last reply whatever came before`() {
        val told = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.CHANNEL_JOINED))
        val lost = assertNotNull(spendReply(budgetFullAt = told, now = T + MINUTE, reply = Reply.EXPENSE_KEYS))

        assertNull(spendReply(budgetFullAt = lost, now = T + 11 * MINUTE, reply = Reply.EXPENSE_KEYS))
        assertNotNull(spendReply(budgetFullAt = lost, now = T + MINUTE + HOUR, reply = Reply.EXPENSE_KEYS))
    }

    @Test
    fun `a channel_joined alone goes out once an hour`() {
        val told = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.CHANNEL_JOINED))

        assertNull(spendReply(budgetFullAt = told, now = T + HOUR - 1, reply = Reply.CHANNEL_JOINED))
        assertNotNull(spendReply(budgetFullAt = told, now = T + HOUR, reply = Reply.CHANNEL_JOINED))
    }

    /** A peer asking every minute, with a Welcome every time, for three days. */
    @Test
    fun `no peer is sent more than 25 replies in a day`() {
        var budgetFullAt: Long? = null
        val sent = mutableListOf<Long>()

        var now = T
        while (now < T + 3 * DAY) {
            for (reply in listOf(Reply.EXPENSE_KEYS, Reply.CHANNEL_JOINED)) {
                spendReply(budgetFullAt = budgetFullAt, now = now, reply = reply)?.let {
                    budgetFullAt = it
                    sent += now
                }
            }

            now += MINUTE
        }

        val most = sent.maxOf { start -> sent.count { it in start until start + DAY } }

        assertEquals(25, most)
    }

    @Test
    fun `a budget pushed out of reach by the clock going back counts as whole`() {
        assertEquals(T + HOUR, spendReply(budgetFullAt = T + 10 * HOUR, now = T, reply = Reply.EXPENSE_KEYS))
    }

    /** As far as two replies at once push it, which is not the clock going back. */
    @Test
    fun `a budget spent to the full is not taken for the clock going back`() {
        assertNull(spendReply(budgetFullAt = T + 2 * HOUR, now = T, reply = Reply.EXPENSE_KEYS))
    }
}
