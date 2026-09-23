package app.lidory.duckcount.expenseList.callBacks

import androidx.paging.PagingData
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import kotlinx.coroutines.flow.Flow

interface ExpenseListCallBacks {

    /** Paged one-time expenses, server-synced and backed by the local DB. */
    fun getOneTimeExpensesPaged(): Flow<PagingData<ExpenseWithBeneficiaries>>

    suspend fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries>

    suspend fun insert(expense: Expense)

    suspend fun getAllUsersFiltered(filter: String): Flow<List<User>>

    suspend fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>>

    suspend fun insertBeneficiary(beneficiary: Beneficiary)

    fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int?

    fun getAllExpensesWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllExpensesFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getUserFullName(userUuid: String): Flow<String?>

    fun getAuthenticatedUserUuid(): String?

    /**
     * The list of one-time expenses is on screen, scrolled to the newest.
     *
     * Until [newestExpensesHidden], an expense somebody shares goes in where the user is
     * already looking. So the notifications about expenses come down, and no new ones go up.
     */
    fun newestExpensesShown()

    /**
     * The newest one-time expenses left the screen, with the list or by scrolling, so arriving
     * expenses are announced again.
     */
    fun newestExpensesHidden()
}
