package expense

import database.data.remote.BeneficiaryDto
import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseDto
import database.data.remote.OneTimeExpenseRequestBody
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.ExpenseWithBeneficiaries
import kotlinx.serialization.json.Json
import vault.sealBound
import vault.unsealBound

private val json = Json { ignoreUnknownKeys = true }

/**
 * What each sealed field of a one-time expense is bound to.
 *
 * Every field of one expense is sealed under the same key, so without this they would all
 * be interchangeable: the server could put Bob's share where Carol's was and both would
 * decrypt perfectly, into a different answer to who owes what, with nothing recording that
 * it happened. The key already binds the expense; naming the field, and for a share the
 * beneficiary it belongs to, binds the rest.
 */
private object Context {
    const val TITLE = "one_time_expense:title"
    const val DESCRIPTION = "one_time_expense:description"
    const val AMOUNT = "one_time_expense:amount"
    const val CURRENCY = "one_time_expense:currency"
    const val TIMESTAMP = "one_time_expense:timestamp"

    fun share(userUuid: String) = "one_time_expense:share:$userUuid"

    fun shareFixed(userUuid: String) = "one_time_expense:share_fixed:$userUuid"
}

/**
 * The value sent for the server's `master_key` column, which this protocol does not use.
 *
 * The column holds one blob per expense and every reader is served the same bytes, so
 * there is nothing a creditor could put there that a beneficiary could open and the server
 * could not. The key reaches beneficiaries over their own channel instead, and each of them
 * keeps it in their own vault. The column stays filled because the server requires it.
 */
private val UNUSED_MASTER_KEY = EncryptedFieldDto(ciphertext = "AA==", nonce = "AA==")

/**
 * Seals this expense under [expenseKey], ready for `POST /expenses` or `PUT /expenses/{uuid}`.
 *
 * Returns null only when [expenseKey] is not a 32-byte key, which is a caller bug rather
 * than a state: every key either comes from `generateSymmetricEncryptionKey` or was read
 * back out of a record that one wrote.
 *
 * The key is a parameter rather than something minted here, so that the update path
 * physically cannot seal an expense under a key its beneficiaries do not have.
 */
internal fun ExpenseWithBeneficiaries.sealOneTime(expenseKey: ByteArray): OneTimeExpenseRequestBody? {
    val sealedBeneficiaries = beneficiaries.map { beneficiary ->
        BeneficiaryDto(
            user_uuid = beneficiary.userUuid,
            share = sealBound(
                key = expenseKey,
                context = Context.share(userUuid = beneficiary.userUuid),
                plaintext = beneficiary.share.encodeToByteArray(),
            ) ?: return null,
            is_share_fixed = sealBound(
                key = expenseKey,
                context = Context.shareFixed(userUuid = beneficiary.userUuid),
                plaintext = beneficiary.isShareFixed.toString().encodeToByteArray(),
            ) ?: return null,
        )
    }

    return OneTimeExpenseRequestBody(
        master_key = UNUSED_MASTER_KEY,
        title = sealBound(expenseKey, Context.TITLE, title.encodeToByteArray()) ?: return null,
        description = sealBound(expenseKey, Context.DESCRIPTION, description.encodeToByteArray())
            ?: return null,
        amount = sealBound(expenseKey, Context.AMOUNT, amount.encodeToByteArray()) ?: return null,
        currency = sealBound(expenseKey, Context.CURRENCY, currency.serialize()) ?: return null,
        timestamp = sealBound(expenseKey, Context.TIMESTAMP, timestamp.encodeToByteArray())
            ?: return null,
        is_settled = isSettlement,
        beneficiaries = sealedBeneficiaries,
    )
}

/**
 * Opens what the server holds for this expense, or returns null when [expenseKey] does not
 * open it or any part of it has been moved from where it was sealed.
 *
 * All or nothing on purpose: a half-opened expense would be a row with a real title and a
 * made-up amount, fed to the debt algorithm as though it were the user's own. One that does
 * not open whole is treated as one whose key is still missing, which the user can see.
 */
internal fun ExpenseDto.unsealOneTime(expenseKey: ByteArray): ExpenseWithBeneficiaries? {
    val openedBeneficiaries = beneficiaries.map { beneficiary ->
        val share = unsealBound(
            key = expenseKey,
            context = Context.share(userUuid = beneficiary.user_uuid),
            content = beneficiary.share,
        )?.decodeToString() ?: return null

        val isShareFixed = unsealBound(
            key = expenseKey,
            context = Context.shareFixed(userUuid = beneficiary.user_uuid),
            content = beneficiary.is_share_fixed,
        )?.decodeToString()?.toBooleanStrictOrNull() ?: return null

        Beneficiary(
            expenseUuid = uuid,
            userUuid = beneficiary.user_uuid,
            share = share,
            isShareFixed = isShareFixed,
        )
    }

    return ExpenseWithBeneficiaries(
        uuid = uuid,
        title = unsealBound(expenseKey, Context.TITLE, title)?.decodeToString() ?: return null,
        description = unsealBound(expenseKey, Context.DESCRIPTION, description)?.decodeToString()
            ?: return null,
        amount = unsealBound(expenseKey, Context.AMOUNT, amount)?.decodeToString() ?: return null,
        currency = unsealBound(expenseKey, Context.CURRENCY, currency)?.toCurrency() ?: return null,
        timestamp = unsealBound(expenseKey, Context.TIMESTAMP, timestamp)?.decodeToString()
            ?: return null,
        creditorUuid = creditor_uuid,
        isSettlement = is_settled,
        // A one-time expense is by definition not in a group, and the server hard-deletes
        // rather than tombstoning, so a row that came back is a row that exists.
        groupUuid = null,
        isDeleted = false,
        beneficiaries = openedBeneficiaries,
    )
}

/**
 * Whether [candidate] is the key this expense was sealed with.
 *
 * Trial-decryption rather than anything the sender said, because what has to be ruled out
 * is a sender who lies: a co-beneficiary who sends thirty-two random bytes would otherwise
 * have them stored as the key, and the device would stop asking for the real one. The title
 * is as good a witness as any field, and the binding means it can only have come from this
 * expense.
 */
internal fun ExpenseDto.opensWith(candidate: ByteArray): Boolean =
    unsealBound(key = candidate, context = Context.TITLE, content = title) != null

/**
 * The currency itself, not a reference to one: a catalogue id is only worth something to a
 * device holding the same catalogue, and a currency the user added is in nobody else's.
 */
private fun Currency.serialize(): ByteArray =
    json.encodeToString(Currency.serializer(), this).encodeToByteArray()

private fun ByteArray.toCurrency(): Currency? =
    runCatching { json.decodeFromString(Currency.serializer(), decodeToString()) }.getOrNull()
