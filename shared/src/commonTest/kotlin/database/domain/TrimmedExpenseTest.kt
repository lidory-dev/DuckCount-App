package database.domain

import kotlin.test.Test
import kotlin.test.assertEquals

private const val EXPENSE = "8d1e5823-c00c-83b7-8212-500089e3b975"
private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

/**
 * An expense as the user's own writes take it, see [trimmed]: its title and description without
 * the whitespace around them, as the form holds them typed.
 */
class TrimmedExpenseTest {

    /** A space typed at the end of the title, or a line left after the description. */
    @Test
    fun `an expense is taken without the whitespace around its title and description`() {
        val expense = expense(title = "  Dinner ", description = "\nPizza and wine\n\n")

        assertEquals(expense.copy(title = "Dinner", description = "Pizza and wine"), expense.trimmed())
    }

    @Test
    fun `the whitespace inside a title or a description is kept`() {
        val expense = expense(title = "Dinner  at Ada's", description = "Pizza\n\nWine")

        assertEquals(expense, expense.trimmed())
    }

    @Test
    fun `a title or a description of nothing but whitespace is taken as empty`() {
        val expense = expense(title = "   ", description = "\n\t \n")

        assertEquals(expense.copy(title = "", description = ""), expense.trimmed())
    }

    private fun expense(title: String, description: String) =
        ExpenseWithBeneficiaries(
            uuid = EXPENSE,
            title = title,
            timestamp = "2026-10-29T12:00:00",
            description = description,
            amount = "20",
            creditorUuid = ALICE,
            currency = CurrencyCatalog.defaultCurrency,
            beneficiaries = listOf(
                Beneficiary(uuid = "b1", expenseUuid = EXPENSE, userUuid = ALICE, share = "10", isShareFixed = false),
                Beneficiary(uuid = "b2", expenseUuid = EXPENSE, userUuid = BOB, share = "10", isShareFixed = false),
            ),
        )
}
