package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * One version of a one-time expense, by its revision. Only which one: what it says is read
 * again rather than kept here, where it would be saved with the screen's state.
 */
@Serializable
data class ExpenseHistoryDetailScreenKey(
    val expenseUuid: String,
    val revision: Long,
) : NavKey
