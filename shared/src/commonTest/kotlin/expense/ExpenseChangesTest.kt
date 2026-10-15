package expense

import database.data.local.dao.ShownOneTimeExpense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"
private const val EXPENSE_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
private const val EXPENSE_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

/** Alice is the user, Bob a contact of hers and her creditor, Carol a former contact. */
class ExpenseChangesTest {

    /** Deleted, or Alice taken off it: either way the sync no longer finds a row. */
    @Test
    fun `an expense gone after the sync is removed and named as it was shown`() {
        val changes = changesBetween(
            before = snapshotOf(shown(title = "Dinner")),
            after = snapshotOf(),
            contacts = setOf(BOB),
        )

        assertEquals(listOf(EXPENSE_A to ExpenseChange.Kind.REMOVED), changes.kinds())
        assertEquals("Dinner", changes.single().first.title)
    }

    @Test
    fun `a newer revision is an edit named by its new title`() {
        val changes = changesBetween(
            before = snapshotOf(shown(title = "Dinner", revision = 1)),
            after = snapshotOf(shown(title = "Dinner and drinks", revision = 2)),
            contacts = setOf(BOB),
        )

        assertEquals(listOf(EXPENSE_A to ExpenseChange.Kind.EDITED), changes.kinds())
        assertEquals("Dinner and drinks", changes.single().first.title)
    }

    @Test
    fun `a mark saying it was paid back is marked settled`() {
        val changes = changesBetween(
            before = snapshotOf(shown(isSettled = false)),
            after = snapshotOf(shown(isSettled = true)),
            contacts = setOf(BOB),
        )

        assertEquals(listOf(EXPENSE_A to ExpenseChange.Kind.MARKED_SETTLED), changes.kinds())
    }

    @Test
    fun `a mark taking that back is marked not settled`() {
        val changes = changesBetween(
            before = snapshotOf(shown(isSettled = true)),
            after = snapshotOf(shown(isSettled = false)),
            contacts = setOf(BOB),
        )

        assertEquals(listOf(EXPENSE_A to ExpenseChange.Kind.MARKED_NOT_SETTLED), changes.kinds())
    }

    @Test
    fun `an edit and a mark brought in by one sync are one change`() {
        assertEquals(
            listOf(EXPENSE_A to ExpenseChange.Kind.EDITED_AND_MARKED_SETTLED),
            changesBetween(
                before = snapshotOf(shown(revision = 1, isSettled = false)),
                after = snapshotOf(shown(revision = 2, isSettled = true)),
                contacts = setOf(BOB),
            ).kinds(),
        )

        assertEquals(
            listOf(EXPENSE_A to ExpenseChange.Kind.EDITED_AND_MARKED_NOT_SETTLED),
            changesBetween(
                before = snapshotOf(shown(revision = 1, isSettled = true)),
                after = snapshotOf(shown(revision = 2, isSettled = false)),
                contacts = setOf(BOB),
            ).kinds(),
        )
    }

    @Test
    fun `an expense shown the same after the sync is no change`() {
        val changes = changesBetween(
            before = snapshotOf(shown(revision = 2, isSettled = true)),
            after = snapshotOf(shown(revision = 2, isSettled = true)),
            contacts = setOf(BOB),
        )

        assertTrue(changes.isEmpty())
    }

    /**
     * Bob marking it settled again, after an answer he never got for the first mark: a newer
     * mark is stored, and says what the one before it said.
     */
    @Test
    fun `a mark signed again saying the same is no change`() {
        val changes = changesBetween(
            before = snapshotOf(shown(isSettled = true)),
            after = snapshotOf(shown(isSettled = true)),
            contacts = setOf(BOB),
        )

        assertTrue(changes.isEmpty())
    }

    /** Made by Alice on another of her devices, and brought in by this one's sync. */
    @Test
    fun `the user's own expenses are never told about`() {
        val changes = changesBetween(
            before = snapshotOf(
                shown(uuid = EXPENSE_A, creditorUuid = ALICE, revision = 1, isSettled = false),
                shown(uuid = EXPENSE_B, creditorUuid = ALICE),
            ),
            after = snapshotOf(
                shown(uuid = EXPENSE_A, creditorUuid = ALICE, revision = 2, isSettled = true),
            ),
            contacts = setOf(BOB),
        )

        assertTrue(changes.isEmpty())
    }

    /**
     * Carol's expense came in while she was a contact. What she does to it since still shows in
     * the app, and nothing tells Alice about it.
     */
    @Test
    fun `a former contact's changes are never told about`() {
        val changes = changesBetween(
            before = snapshotOf(
                shown(uuid = EXPENSE_A, creditorUuid = CAROL, isSettled = false),
                shown(uuid = EXPENSE_B, creditorUuid = CAROL),
            ),
            after = snapshotOf(
                shown(uuid = EXPENSE_A, creditorUuid = CAROL, isSettled = true),
            ),
            contacts = setOf(BOB),
        )

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `an expense that became readable is an arrival and no change`() {
        val changes = changesBetween(
            before = snapshotOf(shown(uuid = EXPENSE_A)),
            after = snapshotOf(shown(uuid = EXPENSE_A), shown(uuid = EXPENSE_B, isSettled = true)),
            contacts = setOf(BOB),
        )

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `each expense that changed is told about once`() {
        val changes = changesBetween(
            before = snapshotOf(shown(uuid = EXPENSE_A, revision = 1), shown(uuid = EXPENSE_B)),
            after = snapshotOf(shown(uuid = EXPENSE_A, revision = 2)),
            contacts = setOf(BOB),
        )

        assertEquals(
            setOf(EXPENSE_A to ExpenseChange.Kind.EDITED, EXPENSE_B to ExpenseChange.Kind.REMOVED),
            changes.kinds().toSet(),
        )
        assertEquals(2, changes.size)
    }

    private fun shown(
        uuid: String = EXPENSE_A,
        title: String = "Dinner",
        creditorUuid: String = BOB,
        revision: Long = 1,
        isSettled: Boolean = false,
    ) = ShownOneTimeExpense(
        uuid = uuid,
        title = title,
        creditorUuid = creditorUuid,
        revision = revision,
        isSettled = isSettled,
    )

    private fun snapshotOf(vararg expenses: ShownOneTimeExpense) =
        ShownExpenseSnapshot(shown = expenses.associateBy { it.uuid })

    private fun List<Pair<ShownOneTimeExpense, ExpenseChange.Kind>>.kinds() =
        map { (expense, kind) -> expense.uuid to kind }
}
