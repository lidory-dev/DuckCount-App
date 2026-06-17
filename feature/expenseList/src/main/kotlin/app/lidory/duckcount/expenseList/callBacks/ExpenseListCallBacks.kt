package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import kotlinx.coroutines.flow.Flow

interface ExpenseListCallBacks {

    suspend fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries>

    suspend fun insert(expense: Expense)

    suspend fun getAllUsersFiltered(filter: String): Flow<List<User>>

    suspend fun getAllUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>>

    suspend fun insertBeneficiary(beneficiary: Beneficiary)

    fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int?

    fun getAllExpensesWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllExpensesFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getUserFullName(userUuid: String): Flow<String?>

    suspend fun getConnectedUserUuid(): String?
}