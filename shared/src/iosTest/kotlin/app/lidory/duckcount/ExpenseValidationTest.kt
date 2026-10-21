package app.lidory.duckcount

import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The beneficiary cap as the app reads it from the library, and the error [validateExpense]
 * turns the library's refusal into.
 */
class ExpenseValidationTest {

    /** [count] distinct beneficiaries owing one euro each. */
    private fun beneficiaries(count: Int): List<Beneficiary> =
        List(count) { index ->
            Beneficiary(
                expenseUuid = "",
                userUuid = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                share = "1.00",
                isShareFixed = false,
            )
        }

    @Test
    fun `the cap is the library's`() {
        // The API holds its own copy of the cap, and the form counts against this one.
        assertEquals(100, ExpenseLimits.MAX_BENEFICIARIES)
    }

    @Test
    fun `an expense listing as many beneficiaries as the cap is valid`() {
        assertNull(
            validateExpense(
                amount = "${ExpenseLimits.MAX_BENEFICIARIES}.00",
                currency = CurrencyCatalog.defaultCurrency,
                beneficiaries = beneficiaries(ExpenseLimits.MAX_BENEFICIARIES),
            ),
        )
    }

    @Test
    fun `an expense listing one beneficiary over the cap has too many`() {
        val count = ExpenseLimits.MAX_BENEFICIARIES + 1

        assertEquals(
            ExpenseError.TOO_MANY_BENEFICIARIES,
            validateExpense(
                amount = "$count.00",
                currency = CurrencyCatalog.defaultCurrency,
                beneficiaries = beneficiaries(count),
            ),
        )
    }
}
