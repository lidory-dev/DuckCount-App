package app.lidory.duckcount.expenseList.viewmodel

import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What an expense's screen shows as this device's copy of it comes and goes, see
 * [ExpenseDetailViewModel.state]: it loads until the expense is first read, and an expense that is
 * gone, deleted by its creditor, is told as such rather than loaded forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseDetailStateTest {

    // The ViewModel reads in its own scope, on the main dispatcher, which a JVM test has none of.
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
    fun `an expense deleted while shown is told as deleted`() = runTest(mainDispatcher) {
        val held = MutableStateFlow<ExpenseWithBeneficiaries?>(expense())
        val states = statesOf(held)

        runCurrent()
        assertTrue(states.last() is ExpenseScreenState.Content)

        held.value = null
        runCurrent()
        assertEquals(ExpenseScreenState.NotFound, states.last())
    }

    // Between a tapped notification, which opens no expense gone already, and the screen's read.
    @Test
    fun `an expense gone by its first read is told as deleted`() = runTest(mainDispatcher) {
        val states = statesOf(MutableStateFlow(null))

        runCurrent()
        assertEquals(ExpenseScreenState.NotFound, states.last())
    }

    @Test
    fun `the screen loads until the expense is first read`() = runTest(mainDispatcher) {
        val states = statesOf(MutableSharedFlow())

        runCurrent()
        assertEquals(listOf(ExpenseScreenState.Loading), states)
    }

    /** Every state the screen of an expense read from [held] shows, as it shows them. */
    private fun TestScope.statesOf(held: Flow<ExpenseWithBeneficiaries?>): List<ExpenseScreenState> {
        val viewModel = ExpenseDetailViewModel(expenseUuid = "expense", expenseDetailCallBacks = callBacks(held))
        val states = mutableListOf<ExpenseScreenState>()

        backgroundScope.launch { viewModel.state.collect { states += it } }

        return states
    }

    private fun callBacks(held: Flow<ExpenseWithBeneficiaries?>) = object : ExpenseDetailCallBacks {
        override val maxGroupExpenseEdits: Int = 10

        override fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> = held

        override suspend fun deleteExpense(expenseUuid: String): ExpenseWriteError? = error("Not deleted")

        override suspend fun setSettled(expenseUuid: String, isSettled: Boolean): ExpenseWriteError? =
            error("Not marked")

        override suspend fun restoreExpense(expenseUuid: String): ExpenseWriteError? = error("Not restored")

        override fun getUserFullName(userUuid: String): Flow<String?> = flowOf(null)

        override fun isGroupLeft(groupUuid: String): Flow<Boolean> = flowOf(false)

        override fun getAuthenticatedUserUuid(): String? = "alice"
    }

    /** A one-time expense of Bob's, which Alice shares. */
    private fun expense() = ExpenseWithBeneficiaries(
        uuid = "expense",
        timestamp = "2026-11-21T12:00:00",
        title = "Groceries",
        description = "",
        amount = "20.00",
        creditorUuid = "bob",
        isSettlement = false,
        currency = Currency(name = "EUR", symbol = "€", hasSubunits = true),
        beneficiaries = listOf("alice", "bob").map {
            Beneficiary(expenseUuid = "expense", userUuid = it, share = "10.00")
        },
        groupUuid = null,
        isDeleted = false,
        revision = 1,
    )
}
