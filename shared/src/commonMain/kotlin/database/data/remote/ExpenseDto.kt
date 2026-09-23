@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Server representation of a one-time expense as returned by `GET /expenses`.
 *
 * Matches the DuckCount-API contract exactly. Every content field is an
 * `EncryptedField` the server cannot read, sealed under the expense's own key,
 * which reaches its participants over their direct-message channels and lives in
 * their vaults; see `expense.OneTimeExpenseSealing`.
 *
 * `is_settled` is the one exception, a plain boolean the server can read.
 * `master_key` is a column this protocol does not use: it holds one blob per
 * expense and hands every reader the same bytes, so there is nothing a creditor
 * could put there that only a beneficiary could open.
 *
 * There is no `group_uuid`: a one-time expense is by definition not in a group.
 * There is no deletion flag either, the server hard-deletes.
 */
@Serializable
data class ExpenseDto(
    val uuid: String,
    val master_key: EncryptedFieldDto,
    val creditor_uuid: String,
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val amount: EncryptedFieldDto,
    val currency: EncryptedFieldDto,
    val timestamp: EncryptedFieldDto,
    val is_settled: Boolean,
    val beneficiaries: List<BeneficiaryDto>,
)
