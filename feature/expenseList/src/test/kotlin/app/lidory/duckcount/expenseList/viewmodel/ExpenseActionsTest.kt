package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import org.junit.Assert.assertEquals
import org.junit.Test

/** The edits an expense of a group takes, which its server holds it to as well. */
private const val MAX_EDITS = 10

/**
 * What the user may do with an expense on its detail screen, see [actions]: nothing in a group they
 * were removed from, no edit of an expense of a group that had every edit it takes, and with a
 * settlement, only delete it, as the one it was paid to.
 */
class ExpenseActionsTest {

    private val alice = "alice"
    private val bob = "bob"
    private val carol = "carol"

    /** Nor its history read, which the server serves to current members alone. */
    @Test
    fun `a group expense in a group the user was removed from can be neither edited nor deleted nor restored`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = false,
                canDelete = false,
                canRestore = false,
                canSettle = false,
                canShowHistory = false,
            ),
            expense(creditorUuid = bob, groupUuid = "group")
                .actions(selfUuid = alice, isGroupLeft = true, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    @Test
    fun `a group expense in a group the user is still in can be edited and deleted and restored`() {
        assertEquals(
            ExpenseActions(
                canEdit = true,
                isEditLimitReached = false,
                canDelete = true,
                canRestore = true,
                canSettle = false,
                canShowHistory = true,
            ),
            expense(creditorUuid = bob, groupUuid = "group")
                .actions(selfUuid = alice, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    /** Created at revision 1, edited ten times since: deleting and restoring it are no edit. */
    @Test
    fun `a group expense at its last revision is no longer edited but still deleted and restored`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = true,
                canDelete = true,
                canRestore = true,
                canSettle = false,
                canShowHistory = true,
            ),
            expense(creditorUuid = bob, groupUuid = "group", revision = 1L + MAX_EDITS)
                .actions(selfUuid = alice, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    @Test
    fun `a group expense with one edit left is still edited`() {
        assertEquals(
            ExpenseActions(
                canEdit = true,
                isEditLimitReached = false,
                canDelete = true,
                canRestore = true,
                canSettle = false,
                canShowHistory = true,
            ),
            expense(creditorUuid = bob, groupUuid = "group", revision = MAX_EDITS.toLong())
                .actions(selfUuid = alice, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    /** Nothing is edited there anyway: the screen tells of no edit limit. */
    @Test
    fun `a group expense at its last revision in a group the user left tells no edit limit`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = false,
                canDelete = false,
                canRestore = false,
                canSettle = false,
                canShowHistory = false,
            ),
            expense(creditorUuid = bob, groupUuid = "group", revision = 1L + MAX_EDITS)
                .actions(selfUuid = alice, isGroupLeft = true, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    @Test
    fun `a one-time expense is edited and deleted and marked as paid back by its creditor`() {
        assertEquals(
            ExpenseActions(
                canEdit = true,
                isEditLimitReached = false,
                canDelete = true,
                canRestore = true,
                canSettle = true,
                canShowHistory = true,
            ),
            expense(creditorUuid = alice, groupUuid = null)
                .actions(selfUuid = alice, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    /**
     * A one-time expense's revision is reserved for every edit tried, those the server refused or
     * never answered included, see `ExpenseRepository.updateOneTimeExpense`: it can stand past the
     * last revision of a group's expense with edits left, which its server counts by those it took.
     */
    @Test
    fun `a one-time expense past the group edit count is still edited by its creditor and tells no edit limit`() {
        assertEquals(
            ExpenseActions(
                canEdit = true,
                isEditLimitReached = false,
                canDelete = true,
                canRestore = true,
                canSettle = true,
                canShowHistory = true,
            ),
            expense(creditorUuid = alice, groupUuid = null, revision = 1L + MAX_EDITS)
                .actions(selfUuid = alice, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    @Test
    fun `a one-time expense is neither edited nor deleted by one of its beneficiaries`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = false,
                canDelete = false,
                canRestore = true,
                canSettle = false,
                canShowHistory = true,
            ),
            expense(creditorUuid = bob, groupUuid = null)
                .actions(selfUuid = alice, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    /** Bob paid Alice back, which she recorded, its one beneficiary. */
    @Test
    fun `a settlement is deleted by its recipient and neither edited nor restored`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = false,
                canDelete = true,
                canRestore = false,
                canSettle = false,
                canShowHistory = false,
            ),
            settlement().actions(selfUuid = alice, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    @Test
    fun `a settlement is not deleted by the member who paid it`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = false,
                canDelete = false,
                canRestore = false,
                canSettle = false,
                canShowHistory = false,
            ),
            settlement().actions(selfUuid = bob, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    @Test
    fun `a settlement is not deleted by another member of the group`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = false,
                canDelete = false,
                canRestore = false,
                canSettle = false,
                canShowHistory = false,
            ),
            settlement().actions(selfUuid = carol, isGroupLeft = false, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    @Test
    fun `a settlement in a group its recipient was removed from is not deleted`() {
        assertEquals(
            ExpenseActions(
                canEdit = false,
                isEditLimitReached = false,
                canDelete = false,
                canRestore = false,
                canSettle = false,
                canShowHistory = false,
            ),
            settlement().actions(selfUuid = alice, isGroupLeft = true, maxGroupExpenseEdits = MAX_EDITS),
        )
    }

    private fun expense(creditorUuid: String, groupUuid: String?, revision: Long = 1) = ExpenseWithBeneficiaries(
        uuid = "expense",
        timestamp = "2026-10-26T12:00:00Z",
        title = "Groceries",
        description = "",
        amount = "20.00",
        creditorUuid = creditorUuid,
        isSettlement = false,
        currency = Currency(name = "EUR", symbol = "€", hasSubunits = true),
        beneficiaries = listOf(alice, bob).map { Beneficiary(expenseUuid = "expense", userUuid = it, share = "10.00") },
        groupUuid = groupUuid,
        isDeleted = false,
        revision = revision,
    )

    /** Paid by Bob, its creditor, to Alice, its one beneficiary. */
    private fun settlement() = ExpenseWithBeneficiaries(
        uuid = "settlement",
        timestamp = "2026-10-26T12:00:00",
        title = "",
        description = "",
        amount = "20.00",
        creditorUuid = bob,
        isSettlement = true,
        currency = Currency(name = "EUR", symbol = "€", hasSubunits = true),
        beneficiaries = listOf(
            Beneficiary(expenseUuid = "settlement", userUuid = alice, share = "20.00", isShareFixed = true),
        ),
        groupUuid = "group",
        isDeleted = false,
        revision = 1,
    )
}
