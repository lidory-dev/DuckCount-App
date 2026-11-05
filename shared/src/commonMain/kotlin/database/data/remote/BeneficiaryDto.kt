@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Server representation of a beneficiary.
 * `share`, `is_share_fixed` and `share_percentage` are opaque to the server.
 */
@Serializable
data class BeneficiaryDto(
    val user_uuid: String,
    val share: EncryptedFieldDto,
    val is_share_fixed: EncryptedFieldDto,
    val share_percentage: EncryptedFieldDto,
)
