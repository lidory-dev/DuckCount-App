package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * [instanceId] makes each opening of the expense an entry of its own. Entries with equal keys
 * share their ViewModel, and a deleted or restored expense stays in the list, so opening it again
 * while the last detail is still closing, or after a configuration change cut its closing short,
 * would hand it the ViewModel of the detail just closed, and it would close at once.
 */
@Serializable
data class ExpenseDetailScreenKey(
    val groupUuid: String?,
    val expenseUuid: String,
    val instanceId: String,
) : NavKey {
    constructor(groupUuid: String?, expenseUuid: String) :
        this(groupUuid, expenseUuid, instanceId = Uuid.random().toString())
}
