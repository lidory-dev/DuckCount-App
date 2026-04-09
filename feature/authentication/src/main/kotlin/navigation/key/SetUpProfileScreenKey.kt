package navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data class SetUpProfileScreenKey(
    val uuid: String,
    val username: String,
    val hasPasswordBeenChecked: Boolean,
) : NavKey
