package expense

import database.data.remote.BeneficiaryDto
import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseDto
import database.data.remote.OneTimeExpenseRequestBody
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.ExpenseWithBeneficiaries
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.OneTimeExpenseBeneficiary
import uniffi.lib_duckcount.OneTimeExpenseManifest
import uniffi.lib_duckcount.SealedValue
import uniffi.lib_duckcount.verifyOneTimeExpense
import vault.sealBound
import vault.unsealBound
import kotlin.io.encoding.Base64

private val json = Json { ignoreUnknownKeys = true }

/** The revision an expense is created at. Each edit adds one. */
internal const val FIRST_REVISION = 0L

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
    const val SIGNATURE = "one_time_expense:signature"

    fun share(userUuid: String) = "one_time_expense:share:$userUuid"

    fun shareFixed(userUuid: String) = "one_time_expense:share_fixed:$userUuid"
}

/**
 * Seals this expense under [expenseKey] and signs it as [revision], ready for
 * `POST /expenses` or `PUT /expenses/{uuid}`.
 *
 * Every participant holds [expenseKey] and could seal a version of their own, so the seal
 * alone does not say who wrote it. The signature does: it is made with [client], the
 * creditor's MLS identity, over everything the server stores, see `one_time_expense` in
 * lib-duckcount.
 *
 * Returns null only when [expenseKey] is not a 32-byte key or signing fails, both caller
 * bugs rather than states: every key either comes from `generateSymmetricEncryptionKey` or
 * was read back out of a record that one wrote.
 *
 * The key is a parameter rather than something minted here, so that the update path
 * physically cannot seal an expense under a key its beneficiaries do not have.
 */
internal fun ExpenseWithBeneficiaries.sealOneTime(
    expenseKey: ByteArray,
    revision: Long,
    client: MlsClient,
): OneTimeExpenseRequestBody? {
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

    val sealedTitle = sealBound(expenseKey, Context.TITLE, title.encodeToByteArray()) ?: return null
    val sealedDescription = sealBound(expenseKey, Context.DESCRIPTION, description.encodeToByteArray())
        ?: return null
    val sealedAmount = sealBound(expenseKey, Context.AMOUNT, amount.encodeToByteArray()) ?: return null
    val sealedCurrency = sealBound(expenseKey, Context.CURRENCY, currency.serialize()) ?: return null
    val sealedTimestamp = sealBound(expenseKey, Context.TIMESTAMP, timestamp.encodeToByteArray())
        ?: return null

    val manifest = oneTimeExpenseManifest(
        revision = revision,
        isSettlement = isSettlement,
        title = sealedTitle,
        description = sealedDescription,
        amount = sealedAmount,
        currency = sealedCurrency,
        timestamp = sealedTimestamp,
        beneficiaries = sealedBeneficiaries,
    ) ?: return null

    val signature = try {
        client.signOneTimeExpense(manifest = manifest)
    } catch (_: MlsException) {
        return null
    }

    return OneTimeExpenseRequestBody(
        title = sealedTitle,
        description = sealedDescription,
        amount = sealedAmount,
        currency = sealedCurrency,
        timestamp = sealedTimestamp,
        is_settled = isSettlement,
        beneficiaries = sealedBeneficiaries,
        signature = sealBound(
            key = expenseKey,
            context = Context.SIGNATURE,
            plaintext = SignedRevision(revision = revision, signature = signature).serialize(),
        ) ?: return null,
    )
}

/** One version of a one-time expense, opened, and which version it is. */
internal class OpenedOneTimeExpense(
    val expense: ExpenseWithBeneficiaries,
    val revision: Long,
)

/**
 * Opens what the server holds for this expense with [record], or returns null when it is
 * not a version [record]'s creditor signed, or does not open whole under [record]'s key.
 *
 * The signature is checked before any field is decrypted, against
 * [OneTimeExpenseKeyRecord.creditorSignatureKey]. That is what the server's `creditor_uuid`,
 * `is_settled` and beneficiary list are believed on: the signature covers them, and a
 * version that the server changed, or that another participant sealed, does not verify.
 *
 * All or nothing on purpose: a half-opened expense would be a row with a real title and a
 * made-up amount, fed to the debt algorithm as though it were the user's own. One that does
 * not open whole is treated as one whose key is still missing.
 */
internal fun ExpenseDto.unsealOneTime(record: OneTimeExpenseKeyRecord): OpenedOneTimeExpense? {
    // Only the creditor who handed the key over can have written a version of this expense.
    if (creditor_uuid != record.creditorUuid) return null

    val signed = unsealBound(record.key, Context.SIGNATURE, signature)
        ?.let { SignedRevision.parse(plaintext = it) }
        ?: return null

    val manifest = oneTimeExpenseManifest(
        revision = signed.revision,
        isSettlement = is_settled,
        title = title,
        description = description,
        amount = amount,
        currency = currency,
        timestamp = timestamp,
        beneficiaries = beneficiaries,
    ) ?: return null

    val isSignedByCreditor = verifyOneTimeExpense(
        creditorUuid = record.creditorUuid,
        creditorSignatureKey = record.creditorSignatureKey,
        manifest = manifest,
        signature = signed.signature,
    )

    if (!isSignedByCreditor) return null

    val expenseKey = record.key

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

    val expense = ExpenseWithBeneficiaries(
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

    return OpenedOneTimeExpense(expense = expense, revision = signed.revision)
}

/**
 * What the creditor signs for one version, built from its sealed fields exactly as the
 * server holds them, or null when one of them is not the Base64 it should be.
 */
private fun oneTimeExpenseManifest(
    revision: Long,
    isSettlement: Boolean,
    title: EncryptedFieldDto,
    description: EncryptedFieldDto,
    amount: EncryptedFieldDto,
    currency: EncryptedFieldDto,
    timestamp: EncryptedFieldDto,
    beneficiaries: List<BeneficiaryDto>,
): OneTimeExpenseManifest? =
    try {
        OneTimeExpenseManifest(
            revision = revision.toULong(),
            isSettlement = isSettlement,
            title = title.toSealedValue(),
            description = description.toSealedValue(),
            amount = amount.toSealedValue(),
            currency = currency.toSealedValue(),
            timestamp = timestamp.toSealedValue(),
            beneficiaries = beneficiaries.map { beneficiary ->
                OneTimeExpenseBeneficiary(
                    userUuid = beneficiary.user_uuid,
                    share = beneficiary.share.toSealedValue(),
                    isShareFixed = beneficiary.is_share_fixed.toSealedValue(),
                )
            },
        )
    } catch (_: IllegalArgumentException) {
        null
    }

private fun EncryptedFieldDto.toSealedValue() = SealedValue(
    nonce = Base64.Default.decode(nonce),
    ciphertext = Base64.Default.decode(ciphertext),
)

/**
 * Which version an expense is, and its creditor's signature over it: what the `signature`
 * field holds, sealed like every other field since nobody else has any use for it.
 */
private class SignedRevision(val revision: Long, val signature: ByteArray) {

    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedSignedRevision.serializer(),
            SerializedSignedRevision(revision = revision, signature = Base64.Default.encode(signature)),
        ).encodeToByteArray()

    companion object {
        fun parse(plaintext: ByteArray): SignedRevision? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedSignedRevision.serializer(),
                    plaintext.decodeToString(),
                )

                SignedRevision(
                    revision = serialized.revision,
                    signature = Base64.Default.decode(serialized.signature),
                )
            }.getOrNull()
    }
}

@Serializable
private class SerializedSignedRevision(
    val revision: Long,
    val signature: String,
)

/**
 * The currency itself, not a reference to one: a catalogue id is only worth something to a
 * device holding the same catalogue, and a currency the user added is in nobody else's.
 */
private fun Currency.serialize(): ByteArray =
    json.encodeToString(Currency.serializer(), this).encodeToByteArray()

private fun ByteArray.toCurrency(): Currency? =
    runCatching { json.decodeFromString(Currency.serializer(), decodeToString()) }.getOrNull()
