@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Server representation of a beneficiary.
 * `share` and `is_share_fixed` are opaque to the server.
 */
@Serializable
data class BeneficiaryDto(
    val user_uuid: String,
    val share: EncryptedFieldDto,
    val is_share_fixed: EncryptedFieldDto,
)
