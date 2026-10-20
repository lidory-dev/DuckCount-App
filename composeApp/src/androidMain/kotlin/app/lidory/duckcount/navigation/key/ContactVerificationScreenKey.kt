package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Where the user compares safety numbers with the contact [userUuid]. */
@Serializable
data class ContactVerificationScreenKey(val userUuid: String) : NavKey
