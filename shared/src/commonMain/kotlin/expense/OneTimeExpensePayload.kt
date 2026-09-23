package expense

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** Unknown keys are ignored, so a field a newer build added does not make a payload unreadable. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * The keys to one or more one-time expenses, handed to somebody they concern.
 *
 * They travel as the plaintext of an MLS application message in the channel the two of them
 * share, so the server never sees them and MLS says who sent them. The expenses themselves
 * go to the server sealed under these keys, and the server is told who each one's
 * participants are but never what it says.
 *
 * Several at once because that is how they are asked for: somebody coming back from a
 * log-out is missing every key a creditor ever sent them, and one message answering all of
 * it costs one of the sender's allowance rather than one per expense.
 */
class ExpenseKeyShare(
    val keys: List<ExpenseKey>,
) {

    /** The plaintext to encrypt for the recipient. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedExpenseKeyShare.serializer(),
            SerializedExpenseKeyShare(
                type = TYPE,
                keys = keys.map {
                    SerializedExpenseKey(
                        expenseUuid = it.expenseUuid,
                        key = Base64.Default.encode(it.key),
                        beneficiaryUuids = it.beneficiaryUuids.sorted(),
                    )
                },
            ),
        ).encodeToByteArray()

    companion object {
        private const val TYPE = "one_time_expense_key"

        /** Reads a share back from a decrypted message, or returns null when it is not one. */
        fun parse(plaintext: ByteArray): ExpenseKeyShare? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedExpenseKeyShare.serializer(),
                    plaintext.decodeToString(),
                )

                if (serialized.type != TYPE) return null

                ExpenseKeyShare(
                    keys = serialized.keys.map {
                        ExpenseKey(
                            expenseUuid = it.expenseUuid,
                            key = Base64.Default.decode(it.key),
                            beneficiaryUuids = it.beneficiaryUuids,
                        )
                    },
                )
            }.getOrNull()
    }
}

/**
 * One expense, the key that opens it, and who its creditor meant it for.
 *
 * The beneficiaries travel with the key because the recipient has to keep them: the same
 * list reaches them from the server on every sync, and the server is exactly what a record
 * of who an expense was meant for must not depend on. Here it arrives inside a message only
 * the creditor could have sent, which the server can neither read nor forge.
 *
 * The creditor is not a field: a share is only ever accepted from the creditor, so it is
 * whoever MLS says sent the message, and a field could only ever disagree with that.
 */
class ExpenseKey(
    val expenseUuid: String,
    val key: ByteArray,
    val beneficiaryUuids: List<String>,
)

/**
 * Asking the creditor of one or more one-time expenses for their keys again.
 *
 * A log-out wipes the local database, which is where MLS keeps its groups, so a key message
 * that arrived while somebody was logged out can never be read: the group it was encrypted
 * to is gone, and MLS offers no way back into a group you have left. The vault brings back
 * the keys that had already arrived, and this asks for the ones that had not.
 *
 * It carries no secret, so it can go over a channel built for the occasion: the asker
 * rebuilds one from the creditor's key package, and the answer comes back in that group.
 */
class ExpenseKeyRequest(
    val expenseUuids: List<String>,
) {

    /** The plaintext to encrypt for the creditor. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedExpenseKeyRequest.serializer(),
            SerializedExpenseKeyRequest(type = TYPE, expenseUuids = expenseUuids),
        ).encodeToByteArray()

    companion object {
        private const val TYPE = "one_time_expense_key_request"

        /**
         * How many expenses one request may name.
         *
         * A bound on what answering one costs, since the answer is built by unsealing a
         * vault record per expense named.
         */
        const val MAX_EXPENSES = 100

        /** Reads a request back from a decrypted message, or returns null when it is not one. */
        fun parse(plaintext: ByteArray): ExpenseKeyRequest? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedExpenseKeyRequest.serializer(),
                    plaintext.decodeToString(),
                )

                if (serialized.type != TYPE) return null

                ExpenseKeyRequest(expenseUuids = serialized.expenseUuids.take(MAX_EXPENSES))
            }.getOrNull()
    }
}

@Serializable
private class SerializedExpenseKeyShare(
    val type: String,
    val keys: List<SerializedExpenseKey>,
)

@Serializable
private class SerializedExpenseKey(
    @SerialName("expense_uuid")
    val expenseUuid: String,

    val key: String,

    @SerialName("beneficiaries")
    val beneficiaryUuids: List<String> = emptyList(),
)

@Serializable
private class SerializedExpenseKeyRequest(
    val type: String,

    @SerialName("expense_uuids")
    val expenseUuids: List<String>,
)
