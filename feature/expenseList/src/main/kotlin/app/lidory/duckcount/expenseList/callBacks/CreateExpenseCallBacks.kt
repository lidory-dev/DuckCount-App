package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.User
import kotlinx.coroutines.flow.Flow

interface CreateExpenseCallBacks {

    fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int?

    fun getAllUsersFiltered(filter: String): Flow<List<User>>

    suspend fun insert(expense: Expense)

    suspend fun insertBeneficiary(beneficiary: Beneficiary)
}