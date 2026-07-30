package app.lidory.duckcount.settings.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @param startOnNavKey opens the flow on a specific screen,
 * with the settings list underneath it.
 */
@Serializable
data class SettingsFlowKey(
    val startOnNavKey: NavKey? = null,
) : NavKey
