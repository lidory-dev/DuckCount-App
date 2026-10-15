package database.data.local.entity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneTimeExpenseSettledMarkEntityTest {

    private val shown = OneTimeExpenseSettledMarkEntity(
        expenseUuid = "8d1e5823-c00c-83b7-8212-500089e3b975",
        markedAt = 1_760_400_000_000,
        isSettled = true,
    )

    @Test
    fun `a newer mark is shown in place of the one shown`() {
        assertTrue(shown.isReplacedBy(markedAt = shown.markedAt + 1))
    }

    /**
     * Only a creditor who signed twice at one time makes two such marks: in one millisecond of
     * their clock, or again after an answer was lost while their clock was behind the newest shown.
     */
    @Test
    fun `another mark made at the same time is not`() {
        assertFalse(shown.isReplacedBy(markedAt = shown.markedAt))
    }

    /** What a server putting back a mark from before the newest looks like. */
    @Test
    fun `an older mark is not shown`() {
        assertFalse(shown.isReplacedBy(markedAt = shown.markedAt - 1))
        assertFalse(shown.isReplacedBy(markedAt = 0))
    }
}
