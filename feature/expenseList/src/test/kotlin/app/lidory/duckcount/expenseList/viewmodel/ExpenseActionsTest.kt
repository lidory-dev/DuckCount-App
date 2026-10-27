package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the user may do with an expense on its detail screen, see [actions]: nothing in a group they were removed from. */
class ExpenseActionsTest {

    private val alice = "alice"
    private val bob = "bob"

    @Test
    fun `a group expense in a group the user was removed from can be neither edited nor deleted nor restored`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = false, canRestore = false, canSettle = false),
            expense(creditorUuid = bob, groupUuid = "group").actions(selfUuid = alice, isGroupLeft = true),
        )
    }

    @Test
    fun `a group expense in a group the user is still in can be edited and deleted and restored`() {
        assertEquals(
            ExpenseActions(canEdit = true, canDelete = true, canRestore = true, canSettle = false),
            expense(creditorUuid = bob, groupUuid = "group").actions(selfUuid = alice, isGroupLeft = false),
        )
    }

    @Test
    fun `a one-time expense is edited and deleted and marked as paid back by its creditor`() {
        assertEquals(
            ExpenseActions(canEdit = true, canDelete = true, canRestore = true, canSettle = true),
            expense(creditorUuid = alice, groupUuid = null).actions(selfUuid = alice, isGroupLeft = false),
        )
    }

    @Test
    fun `a one-time expense is neither edited nor deleted by one of its beneficiaries`() {
        assertEquals(
            ExpenseActions(canEdit = false, canDelete = false, canRestore = true, canSettle = false),
            expense(creditorUuid = bob, groupUuid = null).actions(selfUuid = alice, isGroupLeft = false),
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
}
