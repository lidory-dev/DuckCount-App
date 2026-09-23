@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /expenses` (create) and `PUT /expenses/{uuid}` (update).
 *
 * Same shape for both. The creditor is derived server-side from the token and
 * the `uuid` is server-generated on create, so neither is sent here.
 */
@Serializable
data class OneTimeExpenseRequestBody(
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val amount: EncryptedFieldDto,
    val currency: EncryptedFieldDto,
    val timestamp: EncryptedFieldDto,
    val is_settled: Boolean,
    val beneficiaries: List<BeneficiaryDto>,
    val signature: EncryptedFieldDto,
)
