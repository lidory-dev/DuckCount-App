package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
class CreateExpenseScreenKey(val groupUuid: String?) : NavKey
