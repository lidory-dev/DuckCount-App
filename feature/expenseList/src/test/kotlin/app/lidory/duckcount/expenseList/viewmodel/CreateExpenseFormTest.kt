package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The title and description of the expense form: each field holds its text as typed, which
 * [CreateExpenseViewModel] never trims, while the form is confirmed, see [canConfirmText], and an
 * edit compared with the expense as it was read, see [isChangedFrom], by the text as it is saved,
 * without the whitespace around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateExpenseFormTest {

    // The ViewModel starts reading the form's data on the main dispatcher as it is built, which a
    // JVM test has none of. This one is never run, so nothing it launches happens during a test.
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Built in each test, once the main dispatcher is set.
    private val viewModel by lazy { CreateExpenseViewModel(createExpenseCallBacks = TypingCallBacks()) }

    /** Trimmed as it is typed, the space would be swallowed, and the cursor moved back over it. */
    @Test
    fun `a space typed after the title is held as typed`() {
        viewModel.updateTitle("Lunch ")

        assertEquals("Lunch ", viewModel.state.title)
        assertEquals("Lunch", viewModel.state.trimmedTitle)
    }

    @Test
    fun `a new line typed after the description is held as typed`() {
        viewModel.updateDescription("Pizza\n")

        assertEquals("Pizza\n", viewModel.state.description)
        assertEquals("Pizza", viewModel.state.trimmedDescription)
    }

    @Test
    fun `whitespace inside the title and the description is kept`() {
        val state = form(title = " Team  lunch ", description = "\nPizza\n\nand drinks\n")

        assertEquals("Team  lunch", state.trimmedTitle)
        assertEquals("Pizza\n\nand drinks", state.trimmedDescription)
    }

    @Test
    fun `an expense with a title can be confirmed`() {
        assertTrue(form().canConfirm())
    }

    @Test
    fun `an expense with no title cannot be confirmed`() {
        assertFalse(form(title = "").canConfirm())
    }

    /** It would be saved with no title at all. */
    @Test
    fun `a title of nothing but spaces cannot be confirmed`() {
        assertFalse(form(title = "   ").canConfirm())
    }

    @Test
    fun `a title past its limit cannot be confirmed`() {
        assertFalse(form(title = "a".repeat(MAX_TITLE_LENGTH + 1)).canConfirm())
    }

    @Test
    fun `a description past its limit cannot be confirmed`() {
        assertFalse(form(description = "a".repeat(MAX_DESCRIPTION_LENGTH + 1)).canConfirm())
    }

    /** Counted as it is saved, without the whitespace around it. */
    @Test
    fun `a title past its limit by the whitespace around it alone can be confirmed`() {
        assertTrue(form(title = " ${"a".repeat(MAX_TITLE_LENGTH)} ").canConfirm())
    }

    @Test
    fun `a description past its limit by the whitespace around it alone can be confirmed`() {
        assertTrue(form(description = "${"a".repeat(MAX_DESCRIPTION_LENGTH)}\n").canConfirm())
    }

    @Test
    fun `an edit to the title is a change`() {
        val loaded = form()

        assertTrue(loaded.copy(title = "Dinner").isChangedFrom(loaded))
    }

    @Test
    fun `an edit to the description alone is a change`() {
        val loaded = form()

        assertTrue(loaded.copy(description = "Pizza and drinks").isChangedFrom(loaded))
    }

    /**
     * Saved without it, it would be the same title and description again, spending one of the few
     * edits the server allows a one-time expense.
     */
    @Test
    fun `an edit with nothing but whitespace added around the title and description is no change`() {
        val loaded = form()
        val edited = loaded.copy(title = " $TITLE ", description = "$DESCRIPTION\n\n")

        assertFalse(edited.isChangedFrom(loaded))
    }

    @Test
    fun `an edit changing the whitespace inside the title is a change`() {
        val loaded = form(title = "Team lunch")

        assertTrue(loaded.copy(title = "Team  lunch").isChangedFrom(loaded))
    }

    private fun CreateExpenseState.canConfirm() =
        canConfirmText(maxTitleLength = MAX_TITLE_LENGTH, maxDescriptionLength = MAX_DESCRIPTION_LENGTH)

    private fun form(title: String = TITLE, description: String = DESCRIPTION) = CreateExpenseState(
        title = title,
        description = description,
        currency = EURO,
        validateExpenseCallBack = { _, _, _ -> null },
    )

    private companion object {
        const val MAX_TITLE_LENGTH = 50
        const val MAX_DESCRIPTION_LENGTH = 500
        const val TITLE = "Lunch"
        const val DESCRIPTION = "Pizza"
        val EURO = Currency(name = "Euro", symbol = "€", hasSubunits = true)
    }

    /** Answers what building the form and typing its title and description ask, and nothing else. */
    private class TypingCallBacks : CreateExpenseCallBacks {
        override val maxTitleLength = MAX_TITLE_LENGTH
        override val maxDescriptionLength = MAX_DESCRIPTION_LENGTH
        override val maxBeneficiaries = 100

        override fun getCurrencies(): List<Currency> = listOf(EURO)
        override fun getDefaultCurrency(): Currency = EURO

        override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? =
            notWhileTyping()
        override fun computeBeneficiaries(
            amount: String,
            currency: Currency,
            beneficiaryUserUuids: List<String>,
            fixedUuid: Map<String, String>,
            sharePercentages: Map<String, String>,
            shareWeights: Map<String, String>,
            expenseUuid: String,
        ): List<Beneficiary> = notWhileTyping()
        override fun getCurrencyDisplayName(code: String): String = notWhileTyping()
        override fun getAllUsersFiltered(filter: String): Flow<List<User>> = notWhileTyping()
        override fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> = notWhileTyping()
        override suspend fun insert(expense: Expense) = notWhileTyping()
        override suspend fun insertBeneficiary(beneficiary: Beneficiary) = notWhileTyping()
        override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseSaveError? =
            notWhileTyping()
        override fun getUserFullName(userUuid: String): Flow<String?> = notWhileTyping()
        override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> = notWhileTyping()
        override suspend fun updateExpenseWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseSaveError? =
            notWhileTyping()
        override fun getAuthenticatedUserUuid(): String? = notWhileTyping()
        override fun getGroupCurrency(groupUuid: String): Flow<Currency> = notWhileTyping()

        private fun notWhileTyping(): Nothing = error("Not asked while the title and description are typed")
    }
}
