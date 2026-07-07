package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data class ExpenseHistoryDetailScreenKey(
    val groupUuid: String?,
    val expenseUuid: String,
    val expenseIndex: Int,
) : NavKey
