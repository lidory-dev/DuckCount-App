package app.lidory.duckcount.settings.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * [instanceId] makes each opening of the screen an entry of its own. Entries with equal keys
 * share their ViewModel, so opening the screen again from the settings list while the last one
 * is still closing, or after a configuration change cut its closing short, would hand it the
 * ViewModel of the reservation just made, and it would close at once.
 */
@Serializable
data class ReserveDiscoveryUsernameScreenKey(val instanceId: String) : NavKey {
    constructor() : this(instanceId = Uuid.random().toString())
}
