package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.flow.Flow

interface ExpenseDetailCallBacks {
    fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?>

    suspend fun deleteExpense(expenseUuid: String)

    suspend fun restoreExpense(expenseUuid: String)

    fun getUserFullName(userUuid: String): Flow<String?>
}