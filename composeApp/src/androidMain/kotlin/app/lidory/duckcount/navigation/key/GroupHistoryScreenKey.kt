package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The history of a group's title and description, reached from its settings. */
@Serializable
data class GroupHistoryScreenKey(val groupUuid: String) : NavKey
