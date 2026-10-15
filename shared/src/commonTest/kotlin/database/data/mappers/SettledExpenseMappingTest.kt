package database.data.mappers

import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.OneTimeExpenseSettledMarkEntity
import database.domain.CurrencyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EXPENSE = "8d1e5823-c00c-83b7-8212-500089e3b975"

class SettledExpenseMappingTest {

    @Test
    fun `an expense nobody marked is not settled`() {
        val expense = expense(settledMark = null).toExpenseWithBeneficiaries()

        assertFalse(expense.isSettled)
        assertNull(expense.settledAt)
    }

    @Test
    fun `an expense marked as paid back is settled since the mark was made`() {
        val expense = expense(settledMark = mark(markedAt = 1_760_400_000_000, isSettled = true))
            .toExpenseWithBeneficiaries()

        assertTrue(expense.isSettled)
        assertEquals(2025, assertNotNull(expense.settledAt).year)
    }

    @Test
    fun `an expense marked as no longer paid back is not settled`() {
        val expense = expense(settledMark = mark(markedAt = 1_760_400_000_000, isSettled = false))
            .toExpenseWithBeneficiaries()

        assertFalse(expense.isSettled)
        assertNull(expense.settledAt)
    }

    /**
     * The creditor signs the time, and nothing stops them signing any. Every time in
     * milliseconds is a date, if an absurd one, so none of them stops the expense being read.
     */
    @Test
    fun `any time the creditor signs reads as a date`() {
        listOf(Long.MAX_VALUE, Long.MIN_VALUE).forEach { markedAt ->
            val expense = expense(settledMark = mark(markedAt = markedAt, isSettled = true))
                .toExpenseWithBeneficiaries()

            assertTrue(expense.isSettled, "$markedAt")
            assertNotNull(expense.settledAt, "$markedAt")
        }
    }

    private fun mark(markedAt: Long, isSettled: Boolean) =
        OneTimeExpenseSettledMarkEntity(expenseUuid = EXPENSE, markedAt = markedAt, isSettled = isSettled)

    private fun expense(settledMark: OneTimeExpenseSettledMarkEntity?) = ExpenseWithBeneficiariesEntity(
        expense = ExpenseEntity(
            uuid = EXPENSE,
            title = "Pizzas",
            timestamp = "2026-10-12T09:00:00",
            description = "",
            amount = "20",
            creditorUuid = "01234567-89ab-4cde-8f01-23456789abcd",
            currency = CurrencyCatalog.defaultCurrency,
        ),
        beneficiaries = emptyList(),
        settledMark = settledMark,
    )
}
