package database.domain

import app.lidory.duckcount.canonicalAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val EURO = Currency(uuid = "euro", name = "EUR", symbol = "€", hasSubunits = true)
private val YEN = Currency(uuid = "yen", name = "JPY", symbol = "¥", hasSubunits = false)

/**
 * What every write puts an expense through before sealing it, whichever kind it is: `12` in a
 * currency without a subunit and `12.00` in one with, for the amount and every share.
 */
class CanonicalAmountsTest {

    private fun expense(amount: String, shares: List<String>, currency: Currency) = ExpenseWithBeneficiaries(
        uuid = "expense",
        title = "Dinner",
        timestamp = "2026-10-30T19:45:00",
        description = "",
        amount = amount,
        creditorUuid = "creditor",
        currency = currency,
        groupUuid = null,
        beneficiaries = shares.mapIndexed { index, share ->
            Beneficiary(expenseUuid = "expense", userUuid = "user $index", share = share, isShareFixed = false)
        },
    )

    private fun ExpenseWithBeneficiaries.amounts() = listOf(amount) + beneficiaries.map { it.share }

    @Test
    fun `an amount is written with its subunits in a currency that has them`() {
        assertEquals("12.00", canonicalAmount(amount = "12", currency = EURO))
        assertEquals("12.50", canonicalAmount(amount = "12.5", currency = EURO))
        assertEquals("12.50", canonicalAmount(amount = "12.50", currency = EURO))
    }

    @Test
    fun `an amount is written in whole units in a currency without subunits`() {
        assertEquals("12", canonicalAmount(amount = "12", currency = YEN))
    }

    @Test
    fun `an amount the library cannot read is none`() {
        assertNull(canonicalAmount(amount = "twelve", currency = EURO))
        assertNull(canonicalAmount(amount = "12.345", currency = EURO))
        assertNull(canonicalAmount(amount = "12.50", currency = YEN))
    }

    @Test
    fun `an expense has its amount and every share written the one way`() {
        val written = assertNotNull(
            expense(amount = "30", shares = listOf("12.5", "17.50"), currency = EURO).withCanonicalAmounts(),
        )

        assertEquals(listOf("30.00", "12.50", "17.50"), written.amounts())
    }

    @Test
    fun `an expense in a currency without subunits stays in whole units`() {
        val written = assertNotNull(
            expense(amount = "30", shares = listOf("12", "18"), currency = YEN).withCanonicalAmounts(),
        )

        assertEquals(listOf("30", "12", "18"), written.amounts())
    }

    @Test
    fun `an expense written the one way already stays as it is`() {
        val written = expense(amount = "30.00", shares = listOf("12.50", "17.50"), currency = EURO)

        assertEquals(written, written.withCanonicalAmounts())
    }

    @Test
    fun `what a share set as a percentage or a weight was set as stays`() {
        val expense = expense(amount = "30", shares = listOf("10"), currency = EURO).let { expense ->
            expense.copy(
                beneficiaries = expense.beneficiaries.map { it.copy(sharePercentage = "33.33", shareWeight = "2") },
            )
        }

        val written = assertNotNull(expense.withCanonicalAmounts()).beneficiaries.single()

        assertEquals("33.33", written.sharePercentage)
        assertEquals("2", written.shareWeight)
    }

    @Test
    fun `an expense with an amount the library cannot read is none`() {
        assertNull(expense(amount = "thirty", shares = listOf("30"), currency = EURO).withCanonicalAmounts())
        assertNull(expense(amount = "30", shares = listOf("thirty"), currency = EURO).withCanonicalAmounts())
    }
}
