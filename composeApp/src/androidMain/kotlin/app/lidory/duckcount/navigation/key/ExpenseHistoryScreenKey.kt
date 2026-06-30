package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data class ExpenseHistoryScreenKey(
    val groupUuid: String?,
    val expenseUuid: String,
) : NavKey
