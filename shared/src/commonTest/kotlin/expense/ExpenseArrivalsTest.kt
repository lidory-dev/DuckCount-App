package expense

import database.data.local.dao.ExpenseCreditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"
private const val EXPENSE_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
private const val EXPENSE_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

/** Alice is the user, Bob a contact of hers, Carol a former contact. */
class ExpenseArrivalsTest {

    @Test
    fun `an expense a contact paid for is announced`() {
        val announced = announcedArrivals(
            arrived = listOf(ExpenseCreditor(uuid = EXPENSE_A, creditorUuid = BOB)),
            contacts = setOf(BOB),
        )

        assertEquals(listOf(EXPENSE_A), announced.map { it.uuid })
    }

    /** The key she still owed Alice when Alice removed her, which opens it all the same. */
    @Test
    fun `an expense a former contact paid for comes in without a word`() {
        val announced = announcedArrivals(
            arrived = listOf(
                ExpenseCreditor(uuid = EXPENSE_A, creditorUuid = CAROL),
                ExpenseCreditor(uuid = EXPENSE_B, creditorUuid = BOB),
            ),
            contacts = setOf(BOB),
        )

        assertEquals(listOf(EXPENSE_B), announced.map { it.uuid })
    }

    /** Readable again once a log-out is behind her, from the copies of the keys she kept. */
    @Test
    fun `the user's own expenses are never announced`() {
        val announced = announcedArrivals(
            arrived = listOf(ExpenseCreditor(uuid = EXPENSE_A, creditorUuid = ALICE)),
            contacts = setOf(BOB),
        )

        assertTrue(announced.isEmpty())
    }
}
