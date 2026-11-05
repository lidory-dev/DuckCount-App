package expense

import app.lidory.duckcount.ExpenseLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.ExpenseLimits.MAX_TITLE_LENGTH
import app.lidory.duckcount.ExpenseLimits.SEALED_AMOUNT_LENGTH
import app.lidory.duckcount.validateExpense
import database.data.remote.BeneficiaryDto
import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseDto
import database.data.remote.OneTimeExpenseRequestBody
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import kotlinx.datetime.LocalDateTime
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

    fun sharePercentage(userUuid: String) = "one_time_expense:share_percentage:$userUuid"

    fun shareWeight(userUuid: String) = "one_time_expense:share_weight:$userUuid"
}

/**
 * Seals this expense under [expenseKey] and signs it as [revision], ready for
 * `PUT /expenses/{uuid}` or, with a nonce, `POST /expenses`. [keyCopy] is the creditor's
 * own copy of the expense's key record, which goes in the same request.
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
    keyCopy: EncryptedFieldDto,
): OneTimeExpenseRequestBody? {
    val sealedBeneficiaries = beneficiaries.map { beneficiary ->
        BeneficiaryDto(
            user_uuid = beneficiary.userUuid,
            share = sealBound(
                key = expenseKey,
                context = Context.share(userUuid = beneficiary.userUuid),
                plaintext = beneficiary.share.toSealedAmount().encodeToByteArray(),
            ) ?: return null,
            is_share_fixed = sealBound(
                key = expenseKey,
                context = Context.shareFixed(userUuid = beneficiary.userUuid),
                plaintext = beneficiary.isShareFixed.toSealedFlag(),
            ) ?: return null,
            share_percentage = sealBound(
                key = expenseKey,
                context = Context.sharePercentage(userUuid = beneficiary.userUuid),
                plaintext = beneficiary.sharePercentage.toSealedShareSetting().encodeToByteArray(),
            ) ?: return null,
            share_weight = sealBound(
                key = expenseKey,
                context = Context.shareWeight(userUuid = beneficiary.userUuid),
                plaintext = beneficiary.shareWeight.toSealedShareSetting().encodeToByteArray(),
            ) ?: return null,
        )
    }

    val sealedTitle = sealBound(expenseKey, Context.TITLE, title.encodeToByteArray()) ?: return null
    val sealedDescription = sealBound(expenseKey, Context.DESCRIPTION, description.encodeToByteArray())
        ?: return null
    val sealedAmount = sealBound(expenseKey, Context.AMOUNT, amount.toSealedAmount().encodeToByteArray()) ?: return null
    val sealedCurrency = sealBound(expenseKey, Context.CURRENCY, currency.uuid.encodeToByteArray()) ?: return null
    val sealedTimestamp = sealBound(expenseKey, Context.TIMESTAMP, timestamp.encodeToByteArray())
        ?: return null

    val manifest = oneTimeExpenseManifest(
        revision = revision,
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
        beneficiaries = sealedBeneficiaries,
        signature = sealBound(
            key = expenseKey,
            context = Context.SIGNATURE,
            plaintext = SignedRevision(revision = revision, signature = signature).serialize(),
        ) ?: return null,
        key_copy = keyCopy,
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
 * [OneTimeExpenseKeyRecord.creditorSignatureKey]. That is what the server's `creditor_uuid`
 * and beneficiary list are trusted on: the signature covers them, and a version that the
 * server changed, or that another participant sealed, does not verify. Whether the expense was
 * paid back is no part of a version, see [openSettledMark].
 *
 * All or nothing on purpose: a half-opened expense would be a row with a real title and a
 * made-up amount, fed to the debt algorithm as though it were the user's own. One that does
 * not open whole is treated as one whose key is still missing.
 *
 * So is one, signed or not, whose title or description is longer than the form allows,
 * whose timestamp is not written exactly as the app writes one, whose currency is not one the
 * app ships with, whose amount or shares were not sealed at one length, see [toAmountOrNull], or
 * whose amount or shares [validateExpense] refuses. The screens read these
 * values as the app writes them, and the expense list is the first thing the app shows: one
 * timestamp it cannot parse would crash it on every launch, with the expense out of the
 * user's reach on the server. The uuids are checked before this, in
 * [OneTimeExpenseSync.refresh], where the listing arrives.
 */
internal fun ExpenseDto.unsealOneTime(record: OneTimeExpenseKeyRecord): OpenedOneTimeExpense? {
    // Only the creditor who handed the key over can have written a version of this expense.
    if (creditor_uuid != record.creditorUuid) return null

    val signed = unsealBound(record.key, Context.SIGNATURE, signature)
        ?.let { SignedRevision.parse(plaintext = it) }
        ?: return null

    val manifest = oneTimeExpenseManifest(
        revision = signed.revision,
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
        )?.decodeToString()?.toAmountOrNull() ?: return null

        val isShareFixed = unsealBound(
            key = expenseKey,
            context = Context.shareFixed(userUuid = beneficiary.user_uuid),
            content = beneficiary.is_share_fixed,
        )?.toFlagOrNull() ?: return null

        val sharePercentage = unsealBound(
            key = expenseKey,
            context = Context.sharePercentage(userUuid = beneficiary.user_uuid),
            content = beneficiary.share_percentage,
        )?.decodeToString() ?: return null

        val shareWeight = unsealBound(
            key = expenseKey,
            context = Context.shareWeight(userUuid = beneficiary.user_uuid),
            content = beneficiary.share_weight,
        )?.decodeToString() ?: return null

        Beneficiary(
            expenseUuid = uuid,
            userUuid = beneficiary.user_uuid,
            share = share,
            isShareFixed = isShareFixed,
            sharePercentage = sharePercentage.toShareSettingOrNull(),
            shareWeight = shareWeight.toShareSettingOrNull(),
        )
    }

    val expense = ExpenseWithBeneficiaries(
        uuid = uuid,
        title = unsealBound(expenseKey, Context.TITLE, title)?.decodeToString()
            ?.takeIf { it.length <= MAX_TITLE_LENGTH }
            ?: return null,
        description = unsealBound(expenseKey, Context.DESCRIPTION, description)?.decodeToString()
            ?.takeIf { it.length <= MAX_DESCRIPTION_LENGTH }
            ?: return null,
        amount = unsealBound(expenseKey, Context.AMOUNT, amount)?.decodeToString()?.toAmountOrNull() ?: return null,
        currency = unsealBound(expenseKey, Context.CURRENCY, currency)?.toCurrency() ?: return null,
        timestamp = unsealBound(expenseKey, Context.TIMESTAMP, timestamp)?.decodeToString()
            ?.takeIf { it.isCanonicalTimestamp() }
            ?: return null,
        creditorUuid = creditor_uuid,
        // A one-time expense is by definition not in a group, and the server hard-deletes
        // rather than tombstoning, so a row that came back is a row that exists.
        groupUuid = null,
        isDeleted = false,
        beneficiaries = openedBeneficiaries,
    )

    // The amount and the shares, by the library's rules for the currency, which also refuse
    // a beneficiary listed twice, or more beneficiaries than an expense may list.
    val error = validateExpense(
        amount = expense.amount,
        currency = expense.currency,
        beneficiaries = expense.beneficiaries,
    )

    if (error != null) return null

    return OpenedOneTimeExpense(expense = expense, revision = signed.revision)
}

/** Whether this is a timestamp written exactly as the app writes one. */
internal fun String.isCanonicalTimestamp(): Boolean =
    LocalDateTime.Formats.ISO.parseOrNull(this)?.let { LocalDateTime.Formats.ISO.format(it) } == this

/**
 * What the creditor signs for one version, built from its sealed fields exactly as the
 * server holds them, or null when one of them is not the Base64 it should be.
 */
private fun oneTimeExpenseManifest(
    revision: Long,
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
                    sharePercentage = beneficiary.share_percentage.toSealedValue(),
                    shareWeight = beneficiary.share_weight.toSealedValue(),
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
 * Whether a share is fixed, as it is sealed: `1` or `0`, and not `true` or `false`, which differ
 * in length. AES-GCM does not hide how long what it seals is, and the server would tell the
 * beneficiaries whose share was set by hand from those paying an even split by the length of
 * this field alone.
 */
private fun Boolean.toSealedFlag(): ByteArray = (if (this) "1" else "0").encodeToByteArray()

/** The flag [toSealedFlag] sealed, or null for anything but `1` or `0`. */
private fun ByteArray.toFlagOrNull(): Boolean? =
    when (decodeToString()) {
        "1" -> true
        "0" -> false
        else -> null
    }

/**
 * An amount or a share as it is sealed, in any currency: padded to [SEALED_AMOUNT_LENGTH], the
 * length of the longest amount there is. AES-GCM does not hide how long what it seals is, and the
 * server would read from the length of this field alone how large an expense is, and how much each
 * beneficiary pays of it. A version of a group expense seals them the same way, see
 * `group.GroupExpenseSealing`. Only what is sealed is padded: everything else, the signature over a
 * group's versions among it, deals with the amount as it is written.
 */
internal fun String.toSealedAmount(): String = padEnd(SEALED_AMOUNT_LENGTH)

/** Whether this is as long as [toSealedAmount] seals an amount. */
internal fun String.isSealedAmount(): Boolean = length == SEALED_AMOUNT_LENGTH

/** The amount [toSealedAmount] padded. What it says is left to [validateExpense]. */
internal fun String.withoutAmountPadding(): String = trimEnd(' ')

/**
 * The amount [toSealedAmount] sealed, or null for what is not as long as it seals one: refused
 * whatever it says, rather than taken from a client whose amounts the server can read the size of,
 * so that a write that came to leave its amounts unpadded shows at once.
 */
internal fun String.toAmountOrNull(): String? = if (isSealedAmount()) withoutAmountPadding() else null

/**
 * The longest a percentage or a weight is written, `100.00`, or anything else [validateExpense]
 * takes: three digits, a point and two more.
 */
private const val SEALED_SHARE_SETTING_LENGTH = 6

/**
 * A share's percentage or weight as it is sealed: padded to one length, and all padding for a share
 * set otherwise. AES-GCM does not hide how long what it seals is, and the server would tell the
 * beneficiaries who pay one from the others by the length of this field alone. A version of a group
 * expense seals them the same way, see `group.GroupExpenseSealing`.
 */
internal fun String?.toSealedShareSetting(): String = orEmpty().padEnd(SEALED_SHARE_SETTING_LENGTH)

/**
 * The percentage or weight [toSealedShareSetting] sealed, or null for a share set otherwise. What it
 * says is left to [validateExpense], which refuses anything it cannot read.
 */
internal fun String.toShareSettingOrNull(): String? = trimEnd().ifEmpty { null }

/**
 * The currency of [CurrencyCatalog] whose UUID was sealed, or null for one the app does not
 * ship with.
 *
 * Only the UUID is sealed: it is the one field the catalogue promises never changes between
 * releases, so the receiver's own entry stands for the rest, and every one is the same length.
 * A symbol, or whether it has subunits, would tell the server which currency an expense is in
 * by the length of this field alone.
 */
private fun ByteArray.toCurrency(): Currency? {
    val uuid = decodeToString()

    return CurrencyCatalog.defaultCurrencies.firstOrNull { it.uuid == uuid }
}
