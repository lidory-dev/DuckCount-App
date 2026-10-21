package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * [instanceId] makes each opening of the form an entry of its own. Entries with equal keys share
 * their ViewModel, so opening the form again while the last one is still closing, or after a
 * configuration change cut its closing short, would hand it the ViewModel of the form just saved,
 * and it would close at once.
 */
@Serializable
data class CreateExpenseScreenKey(
    val groupUuid: String?,
    val expenseUuid: String?,
    val instanceId: String,
) : NavKey {
    constructor(groupUuid: String?, expenseUuid: String?) :
        this(groupUuid, expenseUuid, instanceId = Uuid.random().toString())
}
