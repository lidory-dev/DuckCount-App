package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.PreviousExpenseUi
import kotlinx.coroutines.flow.Flow

interface ExpenseHistoryCallBacks {
    fun getPreviousExpenses(groupUuid: String?, expenseUuid: String): Flow<List<PreviousExpenseUi>>

    fun getCurrency(expenseUuid: String): Flow<Currency?>

    fun getUserFullName(userUuid: String): Flow<String?>
}
