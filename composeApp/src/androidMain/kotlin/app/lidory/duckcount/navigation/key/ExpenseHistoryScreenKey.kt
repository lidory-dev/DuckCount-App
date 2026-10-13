package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The history of a one-time expense: no other expense has one. */
@Serializable
data class ExpenseHistoryScreenKey(
    val expenseUuid: String,
) : NavKey
