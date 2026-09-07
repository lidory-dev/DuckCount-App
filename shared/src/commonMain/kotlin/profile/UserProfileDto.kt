package profile

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `UserProfile`: the three fields it holds for a user, each
 * sealed under that user's profile encryption key and opaque to it.
 *
 * All three are required by the server even though the app only collects a name so
 * far, so the two it has no screen for carry a sealed empty string rather than being
 * omitted. Sealed, not blank: a field the owner has not filled in has to look exactly
 * like one they have, or its ciphertext length would say which is which.
 */
@Serializable
data class UserProfileDto(
    @SerialName("full_name")
    val fullName: EncryptedFieldDto,

    @SerialName("phone_number")
    val phoneNumber: EncryptedFieldDto,

    @SerialName("bank_details")
    val bankDetails: EncryptedFieldDto,
)
