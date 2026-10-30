package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the user may do with an expense on its detail screen, see [actions]: nothing in a group they
 * were removed from, and with a settlement, only delete it, as the one it was paid to.
 */
class ExpenseActionsTest {

    private val alice = "alice"
    private val bob = "bob"
    private val carol = "carol"

    /** Nor its history read, which the server serves to current members alone. */
    @Test
    fun `a group expense in a group the user was removed from can be neither edited nor deleted nor restored`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = false, canRestore = false, canSettle = false, canShowHistory = false),
            expense(creditorUuid = bob, groupUuid = "group").actions(selfUuid = alice, isGroupLeft = true),
        )
    }

    @Test
    fun `a group expense in a group the user is still in can be edited and deleted and restored`() {
        assertEquals(
            ExpenseActions(canEdit = true, canDelete = true, canRestore = true, canSettle = false, canShowHistory = true),
            expense(creditorUuid = bob, groupUuid = "group").actions(selfUuid = alice, isGroupLeft = false),
        )
    }

    @Test
    fun `a one-time expense is edited and deleted and marked as paid back by its creditor`() {
        assertEquals(
            ExpenseActions(canEdit = true, canDelete = true, canRestore = true, canSettle = true, canShowHistory = true),
            expense(creditorUuid = alice, groupUuid = null).actions(selfUuid = alice, isGroupLeft = false),
        )
    }

    @Test
    fun `a one-time expense is neither edited nor deleted by one of its beneficiaries`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = false, canRestore = true, canSettle = false, canShowHistory = true),
            expense(creditorUuid = bob, groupUuid = null).actions(selfUuid = alice, isGroupLeft = false),
        )
    }

    /** Bob paid Alice back, which she recorded, its one beneficiary. */
    @Test
    fun `a settlement is deleted by its recipient and neither edited nor restored`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = true, canRestore = false, canSettle = false, canShowHistory = false),
            settlement().actions(selfUuid = alice, isGroupLeft = false),
        )
    }

    @Test
    fun `a settlement is not deleted by the member who paid it`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = false, canRestore = false, canSettle = false, canShowHistory = false),
            settlement().actions(selfUuid = bob, isGroupLeft = false),
        )
    }

    @Test
    fun `a settlement is not deleted by another member of the group`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = false, canRestore = false, canSettle = false, canShowHistory = false),
            settlement().actions(selfUuid = carol, isGroupLeft = false),
        )
    }

    @Test
    fun `a settlement in a group its recipient was removed from is not deleted`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = false, canRestore = false, canSettle = false, canShowHistory = false),
            settlement().actions(selfUuid = alice, isGroupLeft = true),
        )
    }

    private fun expense(creditorUuid: String, groupUuid: String?) = ExpenseWithBeneficiaries(
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
        beneficiaries = listOf(Beneficiary(expenseUuid = "settlement", userUuid = alice, share = "20.00", isShareFixed = true)),
        groupUuid = "group",
        isDeleted = false,
    )
}
