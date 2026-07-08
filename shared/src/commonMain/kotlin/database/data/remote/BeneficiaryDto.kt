@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Server representation of a beneficiary. `share` is an opaque
 * (end-to-end-encrypted) string.
 *
 * TODO: For now `is_share_fixed` is a client-only
 * concept and is not carried by the server.
 */
@Serializable
data class BeneficiaryDto(
    val user_uuid: String,
    val share: EncryptedFieldDto,
)
