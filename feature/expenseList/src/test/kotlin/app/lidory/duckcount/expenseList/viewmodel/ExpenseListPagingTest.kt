package app.lidory.duckcount.expenseList.viewmodel

import androidx.paging.PagingData
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Which expenses the list pages, see [ExpenseListViewModel.expenses]: those of the group whose tab it
 * is, and the one-time ones on the expenses tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseListPagingTest {

    // The ViewModel keeps the pages read in its own scope, on the main dispatcher, which a JVM test
    // has none of, and only as a test runs this dispatcher for it.
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
    fun `a group's tab pages that group's expenses`() = runTest(mainDispatcher) {
        val callBacks = PagingCallBacks()

        ExpenseListViewModel(expenseListCallBacks = callBacks, groupUuid = "group").expenses.first()

        assertEquals(listOf("group group"), callBacks.read)
    }

    @Test
    fun `the expenses tab pages the one-time expenses`() = runTest(mainDispatcher) {
        val callBacks = PagingCallBacks()

        ExpenseListViewModel(expenseListCallBacks = callBacks, groupUuid = null).expenses.first()

        assertEquals(listOf("one-time"), callBacks.read)
    }

    /** The list's two sources of pages, each noting in [read] that it was read, and nothing else. */
    private class PagingCallBacks : ExpenseListCallBacks {
        val read = mutableListOf<String>()

        override fun getOneTimeExpensesPaged(): Flow<PagingData<ExpenseWithBeneficiaries>> = flow {
            read += "one-time"
            emit(PagingData.empty())
        }

        override fun getGroupExpensesPaged(groupUuid: String): Flow<PagingData<ExpenseWithBeneficiaries>> = flow {
            read += "group $groupUuid"
            emit(PagingData.empty())
        }

        override suspend fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries> = notAsked()

        override suspend fun insert(expense: Expense) { notAsked() }

        override suspend fun getAllUsersFiltered(filter: String): Flow<List<User>> = notAsked()

        override suspend fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> =
            notAsked()

        override suspend fun insertBeneficiary(beneficiary: Beneficiary) { notAsked() }

        override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? =
            notAsked()

        override fun getAllExpensesWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>> = notAsked()

        override fun getUserFullName(userUuid: String): Flow<String?> = notAsked()

        override fun getAuthenticatedUserUuid(): String? = notAsked()

        override fun newestExpensesShown() { notAsked() }

        override fun newestExpensesHidden() { notAsked() }

        private fun notAsked(): Nothing = error("Not asked for the list's pages")
    }
}
