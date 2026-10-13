package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.ExpenseHistory
import kotlinx.coroutines.flow.Flow

interface ExpenseHistoryCallBacks {
    /** The versions of the one-time expense [expenseUuid], or why they could not be read. */
    suspend fun getHistory(expenseUuid: String): ExpenseHistory

    fun getUserFullName(userUuid: String): Flow<String?>
}
