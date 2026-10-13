@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * One entry of `GET /expenses/{uuid}/history`: a version of a one-time expense as it was
 * before an edit replaced it, sealed and signed exactly as the server stored it, and when that
 * edit was made.
 *
 * Every `previous_*` field opens only under the expense's key, and `previous_signature` says,
 * sealed like them, which revision the version was and that its creditor signed it, see
 * `expense.oneTimeExpenseHistory`. [modified_at] is the server's word, in UTC
 * (`2026-10-13T08:15:42.317Z`), and nothing signs it.
 *
 * Two things the server sends or leaves out are not read from it:
 * - `modifier_user_uuid`: only the creditor can sign a version, so the signature says who made
 *   each one, and the server does not get to.
 * - The settlement flag, which the history does not keep, see `expense.unsealOneTime`.
 */
@Serializable
data class OneTimeExpenseHistoryEntryDto(
    val modified_at: String,
    val previous_signature: EncryptedFieldDto,
    val previous_title: EncryptedFieldDto,
    val previous_description: EncryptedFieldDto,
    val previous_amount: EncryptedFieldDto,
    val previous_currency: EncryptedFieldDto,
    val previous_timestamp: EncryptedFieldDto,
    val previous_beneficiaries: List<BeneficiaryDto>,
)
