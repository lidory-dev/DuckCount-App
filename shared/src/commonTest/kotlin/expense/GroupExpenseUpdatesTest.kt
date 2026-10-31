package expense

import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import group.GroupExpenseChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"
private const val DAVE = "77777777-7777-7777-7777-777777777777"
private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val EXPENSE = "bbbbbbbb-bbbb-8bbb-8bbb-bbbbbbbbbbbb"

/**
 * Alice is the user. Bob, Carol and Dave share a group with her, none of them necessarily a
 * contact: whoever writes an expense of it is told about all the same.
 */
class GroupExpenseUpdatesTest {

    @Test
    fun `an expense the user shares in that another member added is told about`() {
        val updates = updatesOf(change(expense = dinner()))

        assertEquals(listOf(GroupExpenseUpdate.Kind.ADDED), updates)
    }

    @Test
    fun `an expense the user paid for that another member added is told about`() {
        val updates = updatesOf(change(expense = dinner(creditorUuid = ALICE, beneficiaries = listOf(BOB, CAROL))))

        assertEquals(listOf(GroupExpenseUpdate.Kind.ADDED), updates)
    }

    /** Nothing changes for her: she neither paid for it nor shares in it. */
    @Test
    fun `an expense the user is not on is not told about`() {
        val updates = updatesOf(change(expense = dinner(creditorUuid = BOB, beneficiaries = listOf(BOB, CAROL))))

        assertTrue(updates.isEmpty())
    }

    /** Written on another of her devices, or before a log-out: nothing she does not know. */
    @Test
    fun `an expense the user wrote herself is not told about`() {
        val updates = updatesOf(change(expense = dinner(), authorUuid = ALICE))

        assertTrue(updates.isEmpty())
    }

    /** After she joined, or a log-out: every expense of the group is new to the device, none news to her. */
    @Test
    fun `nothing of a group listed whole for the first time is told about`() {
        val updates = updatesOf(change(expense = dinner(), isFirstListing = true))

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `nothing a blocked member wrote is told about`() {
        val updates = announcedUpdates(
            changes = listOf(change(expense = dinner(), authorUuid = BOB)),
            selfUuid = ALICE,
            blocked = setOf(BOB),
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `a newer version of an expense shown is an edit`() {
        val updates = updatesOf(change(expense = dinner(title = "Dinner and drinks"), previous = dinner()))

        assertEquals(listOf(GroupExpenseUpdate.Kind.EDITED), updates)
    }

    /** It no longer names her, but did: her balance changed all the same. */
    @Test
    fun `an edit taking the user off an expense is told about`() {
        val updates = updatesOf(
            change(expense = dinner(beneficiaries = listOf(BOB, CAROL)), previous = dinner()),
        )

        assertEquals(listOf(GroupExpenseUpdate.Kind.EDITED), updates)
    }

    /** Carol's deletion flag, the version Bob wrote left as it was. */
    @Test
    fun `a flag deleting the expense shown is a deletion`() {
        val updates = updatesOf(change(expense = dinner(isDeleted = true), previous = dinner(), authorUuid = CAROL, revision = 1))

        assertEquals(listOf(GroupExpenseUpdate.Kind.DELETED), updates)
    }

    @Test
    fun `a flag undoing a deletion is a restoration`() {
        val updates = updatesOf(change(expense = dinner(), previous = dinner(isDeleted = true), authorUuid = CAROL, revision = 1))

        assertEquals(listOf(GroupExpenseUpdate.Kind.RESTORED), updates)
    }

    /** Bob's edit and Carol's deletion, one change of one pass: what moves the user's balances is the deletion. */
    @Test
    fun `an edit and a deletion in one pass are a deletion`() {
        val updates = updatesOf(
            change(expense = dinner(title = "Dinner and drinks", isDeleted = true), previous = dinner(), authorUuid = CAROL),
        )

        assertEquals(listOf(GroupExpenseUpdate.Kind.DELETED), updates)
    }

    /** Created and deleted between two syncs: it counts in no balance, and never did on this device. */
    @Test
    fun `an expense new to the device and deleted already is not told about`() {
        val updates = updatesOf(change(expense = dinner(isDeleted = true)))

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `an edit of an expense that stays deleted is not told about`() {
        val updates = updatesOf(
            change(expense = dinner(title = "Dinner and drinks", isDeleted = true), previous = dinner(isDeleted = true)),
        )

        assertTrue(updates.isEmpty())
    }

    /** Bob, paid back by Alice, records it, as its recipient alone does. */
    @Test
    fun `a settlement the user paid and its deletion are told about`() {
        val settlement = dinner(title = "", creditorUuid = ALICE, beneficiaries = listOf(BOB), isSettlement = true)

        assertEquals(listOf(GroupExpenseUpdate.Kind.ADDED), updatesOf(change(expense = settlement)))
        assertEquals(
            listOf(GroupExpenseUpdate.Kind.DELETED),
            updatesOf(change(expense = settlement.copy(isDeleted = true), previous = settlement)),
        )
    }

    @Test
    fun `each change of a pass is told about apart`() {
        val updates = updatesOf(
            change(expense = dinner()),
            change(expense = dinner(uuid = "cccccccc-cccc-8ccc-8ccc-cccccccccccc", creditorUuid = DAVE)),
        )

        assertEquals(listOf(GroupExpenseUpdate.Kind.ADDED, GroupExpenseUpdate.Kind.ADDED), updates)
    }

    private fun updatesOf(vararg changes: GroupExpenseChange): List<GroupExpenseUpdate.Kind> =
        announcedUpdates(changes = changes.toList(), selfUuid = ALICE, blocked = emptySet()).map { it.second }

    private fun change(
        expense: ExpenseWithBeneficiaries,
        authorUuid: String = BOB,
        previous: ExpenseWithBeneficiaries? = null,
        isFirstListing: Boolean = false,
        revision: Long = if (previous == null) 1 else 2,
    ) = GroupExpenseChange(
        groupUuid = GROUP,
        expense = expense,
        revision = revision,
        authorUuid = authorUuid,
        previous = previous,
        isFirstListing = isFirstListing,
    )

    /** Paid by Bob for him and Alice, unless said otherwise. */
    private fun dinner(
        uuid: String = EXPENSE,
        title: String = "Dinner",
        creditorUuid: String = BOB,
        beneficiaries: List<String> = listOf(ALICE, BOB),
        isSettlement: Boolean = false,
        isDeleted: Boolean = false,
    ) = ExpenseWithBeneficiaries(
        uuid = uuid,
        title = title,
        timestamp = "2026-10-30T20:00:00",
        description = "",
        amount = "40.00",
        creditorUuid = creditorUuid,
        isSettlement = isSettlement,
        currency = CurrencyCatalog.defaultCurrency,
        groupUuid = GROUP,
        isDeleted = isDeleted,
        beneficiaries = beneficiaries.map {
            Beneficiary(expenseUuid = uuid, userUuid = it, share = "${40 / beneficiaries.size}.00", isShareFixed = false)
        },
    )
}
