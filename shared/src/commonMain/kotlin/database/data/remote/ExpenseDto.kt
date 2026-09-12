@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Server representation of a one-time expense as returned by `GET /expenses`.
 *
 * Matches the DuckCount-API contract exactly. `title`, `description`, `amount`
 * and `timestamp` are opaque (end-to-end-encrypted) strings;
 * `master_key` and `is_settled` are encrypted `{ field, nonce }` objects.
 * TODO: There is no `currency`, `group_uuid` or `isDeleted` yet.
 */
@Serializable
data class ExpenseDto(
    val uuid: String,
    val master_key: EncryptedFieldDto,
    val creditor_uuid: String,
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val amount: EncryptedFieldDto,
    val timestamp: EncryptedFieldDto,
    val is_settled: Boolean,
    val beneficiaries: List<BeneficiaryDto>,
)
