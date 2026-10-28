package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import kotlinx.coroutines.flow.Flow

interface ExpenseDetailCallBacks {
    fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?>

    /** Returns null when delete succeeds (server + local), or the reason it failed. */
    suspend fun deleteExpense(expenseUuid: String): ExpenseWriteError?

    /**
     * Marks the user's one-time expense as paid back, or no longer. Returns null when the
     * write succeeds (server + local), or the reason it failed.
     */
    suspend fun setSettled(expenseUuid: String, isSettled: Boolean): ExpenseWriteError?

    suspend fun restoreExpense(expenseUuid: String)

    /** Emits the name to show for [userUuid] as it changes, see [CreateExpenseCallBacks.getUserFullName]. */
    fun getUserFullName(userUuid: String): Flow<String?>

    /**
     * Emits whether the creator removed the user from the group [groupUuid], which stays read-only,
     * its expenses with it.
     */
    fun isGroupLeft(groupUuid: String): Flow<Boolean>

    fun getAuthenticatedUserUuid(): String?
}
