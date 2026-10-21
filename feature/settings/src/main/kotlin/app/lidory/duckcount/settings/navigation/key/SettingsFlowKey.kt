package app.lidory.duckcount.settings.navigation.key

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * @param startOnNavKey opens the flow on a specific screen,
 * with the settings list underneath it.
 */
@Serializable
data class SettingsFlowKey(
    val startOnNavKey: @Serializable(with = StartOnNavKeySerializer::class) NavKey? = null,
) : NavKey

/**
 * A [NavKey] is an interface, which kotlinx.serialization would serialize polymorphically and find
 * no subclass registered for, so saving the back stack would crash as soon as the app went to the
 * background. [NavKeySerializer] stores the key's class name with it instead, as the back stack
 * does for its own keys.
 */
internal object StartOnNavKeySerializer : KSerializer<NavKey> by NavKeySerializer()
