package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.expenseList.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val EXPENSE = "cccccccc-cccc-8ccc-8ccc-cccccccccccc"

private val EURO = Currency(name = "Euro", symbol = "€", hasSubunits = true)

/**
 * What the edit form of a group expense comes to once its save is refused for a version another
 * member made as it was open, see [ExpenseWriteError.CHANGED_MEANWHILE]: read again from the one
 * this device shows now, which the next save is written after. Built on the version it was read
 * from, every save would be refused the same way. And what it comes to once another member deleted
 * the expense, which is no version of it, see [ExpenseSaveError.DeletedMeanwhile].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangedMeanwhileFormTest {

    // As in GroupDetailViewModelTest: nothing the ViewModel launches runs but what a test runs
    // this dispatcher for.
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an edit refused for another member's version reads the form again from it`() = runTest(mainDispatcher) {
        val callBacks = EditCallBacks()
        val viewModel = CreateExpenseViewModel(groupUuid = GROUP, expenseUuid = EXPENSE, createExpenseCallBacks = callBacks)

        runCurrent()

        assertEquals(3L, viewModel.state.revision)

        // Bob's edit reaches the device as Alice types hers.
        callBacks.shown.value = dinner(revision = 4, title = "Bob's dinner")
        viewModel.updateTitle("Alice's dinner")
        viewModel.updateExpense()
        runCurrent()

        assertFalse(viewModel.isSaved.value)
        assertEquals(4L, viewModel.state.revision)
        assertEquals("Bob's dinner", viewModel.state.title)

        viewModel.updateTitle("Alice's dinner")
        viewModel.updateExpense()
        runCurrent()

        assertTrue(viewModel.isSaved.value)
        assertEquals(listOf(3L, 4L), callBacks.saved.map { it.revision })
    }

    /**
     * Bob's deletion reaches the device as Alice types her edit. It took no revision, so the server
     * would take the edit, which would then count in no balance: the form sends nothing, see
     * [CreateExpenseState.isDeleted], and says so rather than to try again.
     */
    @Test
    fun `an edit of an expense another member deleted as it was open is not sent`() = runTest(mainDispatcher) {
        val callBacks = EditCallBacks()
        val viewModel = CreateExpenseViewModel(groupUuid = GROUP, expenseUuid = EXPENSE, createExpenseCallBacks = callBacks)
        val errors = mutableListOf<ExpenseSaveError>()

        backgroundScope.launch { viewModel.showError.collect { errors += it } }
        runCurrent()

        assertFalse(viewModel.state.isDeleted)

        callBacks.shown.value = dinner(revision = 3, title = "Dinner").copy(isDeleted = true, deletedByUuid = BOB)
        viewModel.updateTitle("Alice's dinner")
        viewModel.updateExpense()
        runCurrent()

        assertFalse(viewModel.isSaved.value)
        assertTrue(viewModel.state.isDeleted)
        assertEquals(emptyList<ExpenseWithBeneficiaries>(), callBacks.saved)
        assertEquals(listOf<ExpenseSaveError>(ExpenseSaveError.DeletedMeanwhile), errors)
    }

    /**
     * Bob edited the expense, and deleted it, before the device showed either: Alice's edit is sent,
     * and refused for his version, which the form reads, a deletion it saves nothing over, and says
     * so rather than to try again.
     */
    @Test
    fun `an edit refused for a version of a deleted expense reads it and says so`() = runTest(mainDispatcher) {
        val callBacks = EditCallBacks()
        val viewModel = CreateExpenseViewModel(groupUuid = GROUP, expenseUuid = EXPENSE, createExpenseCallBacks = callBacks)
        val errors = mutableListOf<ExpenseSaveError>()

        backgroundScope.launch { viewModel.showError.collect { errors += it } }
        runCurrent()

        callBacks.unseen = dinner(revision = 4, title = "Bob's dinner").copy(isDeleted = true, deletedByUuid = BOB)
        viewModel.updateTitle("Alice's dinner")
        viewModel.updateExpense()
        runCurrent()

        assertFalse(viewModel.isSaved.value)
        assertTrue(viewModel.state.isDeleted)
        assertEquals(4L, viewModel.state.revision)
        assertEquals(listOf(3L), callBacks.saved.map { it.revision })
        assertEquals(listOf<ExpenseSaveError>(ExpenseSaveError.DeletedMeanwhile), errors)
    }

    /** Alice paid 90.00 for dinner, half of it each for her and Bob, as [revision] of it says, with [title]. */
    private fun dinner(revision: Long, title: String) = ExpenseWithBeneficiaries(
        uuid = EXPENSE,
        timestamp = "2026-10-30T19:45:00",
        title = title,
        description = "",
        amount = "90.00",
        creditorUuid = ALICE,
        isSettlement = false,
        currency = EURO,
        beneficiaries = listOf(
            Beneficiary(expenseUuid = EXPENSE, userUuid = ALICE, share = "45.00"),
            Beneficiary(expenseUuid = EXPENSE, userUuid = BOB, share = "45.00"),
        ),
        groupUuid = GROUP,
        isDeleted = false,
        revision = revision,
    )

    /**
     * Shows the expense as [shown] has it, and takes an edit written after the version the server
     * holds alone, as the server does, refusing any other as another member's having come first,
     * which the sync that follows shows; and nothing else the edit form does not ask.
     */
    private inner class EditCallBacks : CreateExpenseCallBacks {

        val shown = MutableStateFlow(dinner(revision = 3, title = "Dinner"))

        /** What the server holds that this device has yet to show, if anything: [shown] otherwise. */
        var unseen: ExpenseWithBeneficiaries? = null

        /** Every edit sent, in order. */
        val saved = mutableListOf<ExpenseWithBeneficiaries>()

        override val maxTitleLength = 50
        override val maxDescriptionLength = 500
        override val maxBeneficiaries = 100

        override fun getCurrencies(): List<Currency> = listOf(EURO)
        override fun getDefaultCurrency(): Currency = EURO
        override fun getGroupCurrency(groupUuid: String): Flow<Currency> = flowOf(EURO)
        override fun getAuthenticatedUserUuid(): String? = ALICE
        override fun getUserFullName(userUuid: String): Flow<String?> = flowOf(null)
        override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? = null
        override fun getExpenseWithBeneficiaries(expenseUuid: String): Flow<ExpenseWithBeneficiaries> = shown

        override suspend fun updateExpenseWithBeneficiaries(
            expenseWithBeneficiaries: ExpenseWithBeneficiaries,
        ): ExpenseSaveError? {
            saved += expenseWithBeneficiaries

            val held = unseen ?: shown.value

            return if (expenseWithBeneficiaries.revision == held.revision) {
                null
            } else {
                shown.value = held
                unseen = null

                ExpenseSaveError.Write(ExpenseWriteError.CHANGED_MEANWHILE)
            }
        }

        override fun computeBeneficiaries(
            amount: String,
            currency: Currency,
            beneficiaryUserUuids: List<String>,
            fixedUuid: Map<String, String>,
            expenseUuid: String,
        ): List<Beneficiary> = notOnTheEditForm()
        override fun getCurrencyDisplayName(code: String): String = notOnTheEditForm()
        override fun getAllUsersFiltered(filter: String): Flow<List<User>> = notOnTheEditForm()
        override fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> = notOnTheEditForm()
        override suspend fun insert(expense: Expense) = notOnTheEditForm()
        override suspend fun insertBeneficiary(beneficiary: Beneficiary) = notOnTheEditForm()
        override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries): ExpenseSaveError? =
            notOnTheEditForm()

        private fun notOnTheEditForm(): Nothing = error("Not asked as the expense is edited")
    }
}
