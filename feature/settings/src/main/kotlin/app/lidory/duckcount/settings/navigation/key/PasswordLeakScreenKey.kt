package app.lidory.duckcount.settings.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The screen saying the new password typed for a change leaked, [leakAmount] times. */
@Serializable
data class PasswordLeakScreenKey(
    val leakAmount: Int,
) : NavKey
