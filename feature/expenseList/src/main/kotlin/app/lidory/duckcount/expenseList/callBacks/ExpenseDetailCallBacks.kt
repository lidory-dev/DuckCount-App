package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import kotlinx.coroutines.flow.Flow

interface ExpenseDetailCallBacks {
    fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?>

    /** Returns null when delete succeeds (server + local), or the reason it failed. */
    suspend fun deleteExpense(expenseUuid: String): ExpenseWriteError?

    suspend fun restoreExpense(expenseUuid: String)

    fun getUserFullName(userUuid: String): Flow<String?>

    fun getAuthenticatedUserUuid(): String?
}
