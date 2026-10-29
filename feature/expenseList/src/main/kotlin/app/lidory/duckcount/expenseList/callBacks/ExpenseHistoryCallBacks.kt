package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.ExpenseHistory
import kotlinx.coroutines.flow.Flow

interface ExpenseHistoryCallBacks {
    /** The versions of the one-time expense [expenseUuid], or why they could not be read. */
    suspend fun getHistory(expenseUuid: String): ExpenseHistory

    /** Emits the name to show for [userUuid] as it changes, see [CreateExpenseCallBacks.getUserFullName]. */
    fun getUserFullName(userUuid: String): Flow<String?>

    /** The user's own uuid, whom a version lists first among its beneficiaries. */
    fun getAuthenticatedUserUuid(): String?
}
