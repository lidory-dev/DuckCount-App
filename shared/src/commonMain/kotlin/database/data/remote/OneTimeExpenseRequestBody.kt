@file:Suppress("PropertyName")

package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Request body for `PUT /expenses/{uuid}`.
 *
 * The creditor is derived server-side from the token, so it is not sent. `key_copy` is the
 * creditor's own copy of the expense's key record, see `expense.OneTimeExpenseKeyRepository`,
 * which the server stores with the edit it describes.
 */
@Serializable
data class OneTimeExpenseRequestBody(
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val amount: EncryptedFieldDto,
    val currency: EncryptedFieldDto,
    val timestamp: EncryptedFieldDto,
    val beneficiaries: List<BeneficiaryDto>,
    val signature: EncryptedFieldDto,
    val key_copy: EncryptedFieldDto,
) {
    /**
     * The same expense as a `POST /expenses` body. The server derives its UUID from
     * [nonce], see `one_time_expense_id` in lib-duckcount.
     */
    fun toCreateBody(nonce: String) = CreateOneTimeExpenseRequestBody(
        nonce = nonce,
        title = title,
        description = description,
        amount = amount,
        currency = currency,
        timestamp = timestamp,
        beneficiaries = beneficiaries,
        signature = signature,
        key_copy = key_copy,
    )
}

/** Request body for `POST /expenses`, see [OneTimeExpenseRequestBody.toCreateBody]. */
@Serializable
data class CreateOneTimeExpenseRequestBody(
    val nonce: String,
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val amount: EncryptedFieldDto,
    val currency: EncryptedFieldDto,
    val timestamp: EncryptedFieldDto,
    val beneficiaries: List<BeneficiaryDto>,
    val signature: EncryptedFieldDto,
    val key_copy: EncryptedFieldDto,
)

/** Request body for `PUT /expenses/{uuid}/key-copy`. */
@Serializable
data class KeyCopyRequestBody(
    val key_copy: EncryptedFieldDto,
)

/**
 * Request body for `PUT /expenses/{uuid}/settled`: whether the expense was paid back, as the
 * creditor's mark saying so, signed and sealed, see `expense.OneTimeExpenseSettledMark`.
 */
@Serializable
data class SetOneTimeExpenseSettledRequestBody(
    val is_settled: EncryptedFieldDto,
)
