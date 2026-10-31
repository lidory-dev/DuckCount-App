package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import kotlinx.coroutines.flow.Flow

interface ExpenseDetailCallBacks {

    /**
     * The most edits an expense of a group takes, its revision going from 1, as created, to one
     * more than this at most: one at that revision is offered no edit, see
     * [app.lidory.duckcount.expenseList.viewmodel.ExpenseActions.isEditLimitReached], every member's
     * device refusing a version past it, and the server storing none.
     */
    val maxGroupExpenseEdits: Int

    fun getExpenseWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?>

    /** Returns null when delete succeeds (server + local), or the reason it failed. */
    suspend fun deleteExpense(expenseUuid: String): ExpenseWriteError?

    /**
     * Marks the user's one-time expense as paid back, or no longer. Returns null when the
     * write succeeds (server + local), or the reason it failed.
     */
    suspend fun setSettled(expenseUuid: String, isSettled: Boolean): ExpenseWriteError?

    /**
     * Restores the deleted expense of a group, which every member sees restored. Returns null when
     * the write succeeds (server + local), or the reason it failed.
     */
    suspend fun restoreExpense(expenseUuid: String): ExpenseWriteError?

    /** Emits the name to show for [userUuid] as it changes, see [CreateExpenseCallBacks.getUserFullName]. */
    fun getUserFullName(userUuid: String): Flow<String?>

    /**
     * Emits whether the user is no longer a member of the group [groupUuid], removed or left, which
     * stays read-only, its expenses with it.
     */
    fun isGroupLeft(groupUuid: String): Flow<Boolean>

    fun getAuthenticatedUserUuid(): String?
}
