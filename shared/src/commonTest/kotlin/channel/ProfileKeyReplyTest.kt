package channel

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val HOUR = 60L * 60 * 1000

/** Some moment, far enough from zero that nothing below can go negative. */
private const val T = 1_800_000_000_000L

/** The user's profile key handed to a contact who asked, out of the one budget of replies they get. */
class ProfileKeyReplyTest {

    /** What the asker cannot do without, as the keys to expenses: not held back for the whole budget. */
    @Test
    fun `a profile key goes out while one reply is outstanding`() {
        val first = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.EXPENSE_KEYS))

        assertNotNull(spendReply(budgetFullAt = first, now = T, reply = Reply.PROFILE_KEY))
        assertNull(spendReply(budgetFullAt = first, now = T, reply = Reply.CHANNEL_JOINED))
    }

    /** One budget for everything a peer can make this device send: the key takes from the rest. */
    @Test
    fun `a profile key spends the budget the keys to expenses draw on`() {
        val first = assertNotNull(spendReply(budgetFullAt = null, now = T, reply = Reply.PROFILE_KEY))
        val second = assertNotNull(spendReply(budgetFullAt = first, now = T, reply = Reply.PROFILE_KEY))

        assertNull(spendReply(budgetFullAt = second, now = T, reply = Reply.EXPENSE_KEYS))
        assertNotNull(spendReply(budgetFullAt = second, now = T + HOUR, reply = Reply.EXPENSE_KEYS))
    }
}
