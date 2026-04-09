package navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data class PasswordLeakScreenKey(
    val leakAmount: Int,
) : NavKey
