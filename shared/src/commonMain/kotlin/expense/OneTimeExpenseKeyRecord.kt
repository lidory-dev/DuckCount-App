package expense

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** Unknown keys are ignored, so a field a newer build added does not make a record unreadable. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * What a participant keeps for one one-time expense: the key that opens it, whose it is,
 * and who its creditor meant it for.
 *
 * It is a record rather than the bare key because of what the creditor has to decide later.
 * A beneficiary who logs out loses the key with the rest of their local state and has to
 * ask for it again, and the creditor answering that has to know whether the asker is
 * really one of the expense's beneficiaries. Every other account of that comes from the
 * server: `GET /expenses` is the server's word, and the local rows are filled in from it on
 * every sync, so neither can be trusted against a server that wants a key handed out.
 * This is written once, by the creditor, from what they actually picked, and is sealed
 * under a key the server does not have.
 *
 * [expenseUuid] is carried inside rather than being taken from the expense it came with, so
 * that a copy moved to another expense is refused instead of answering for an expense it
 * was never about.
 */
class OneTimeExpenseKeyRecord(
    val expenseUuid: String,
    val key: ByteArray,
    val creditorUuid: String,

    /**
     * The creditor's MLS signature public key, which every version of the expense has to
     * be signed with: the user's own for their own expenses, otherwise taken from the
     * channel the key arrived over, where MLS authenticated the creditor. Either way,
     * checking a version later needs nothing the server provides.
     */
    val creditorSignatureKey: ByteArray,

    /** Sorted, so that two devices writing the same expense write the same bytes. */
    val beneficiaryUuids: List<String>,
) {

    /** Whether [userUuid] is somebody this expense was meant for, creditor included. */
    fun isParticipant(userUuid: String): Boolean =
        userUuid == creditorUuid || userUuid in beneficiaryUuids

    /** The same key to the same expense, meant for [beneficiaryUuids] instead. */
    fun withBeneficiaries(beneficiaryUuids: List<String>): OneTimeExpenseKeyRecord =
        OneTimeExpenseKeyRecord(
            expenseUuid = expenseUuid,
            key = key,
            creditorUuid = creditorUuid,
            creditorSignatureKey = creditorSignatureKey,
            beneficiaryUuids = beneficiaryUuids,
        )

    /** The plaintext to store, and to seal into the user's copy for the server. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedOneTimeExpenseKeyRecord.serializer(),
            SerializedOneTimeExpenseKeyRecord(
                version = VERSION,
                expenseUuid = expenseUuid,
                key = Base64.Default.encode(key),
                creditorUuid = creditorUuid,
                creditorSignatureKey = Base64.Default.encode(creditorSignatureKey),
                beneficiaryUuids = beneficiaryUuids.sorted(),
            ),
        ).encodeToByteArray()

    companion object {
        /** The shape of the record, so a later one can be told apart rather than guessed at. */
        private const val VERSION = 1

        /**
         * Reads a record back, or returns null when it is not one, when it was written by a
         * build this one does not understand, or when it is not about [expenseUuid].
         */
        fun parse(plaintext: ByteArray, expenseUuid: String): OneTimeExpenseKeyRecord? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedOneTimeExpenseKeyRecord.serializer(),
                    plaintext.decodeToString(),
                )

                if (serialized.version != VERSION) return null

                // The expense the record came with and the one it names have to agree, or
                // it is a record about some other expense that has been put here.
                if (serialized.expenseUuid != expenseUuid) return null

                OneTimeExpenseKeyRecord(
                    expenseUuid = serialized.expenseUuid,
                    key = Base64.Default.decode(serialized.key),
                    creditorUuid = serialized.creditorUuid,
                    creditorSignatureKey = Base64.Default.decode(serialized.creditorSignatureKey),
                    beneficiaryUuids = serialized.beneficiaryUuids,
                )
            }.getOrNull()
    }
}

@Serializable
private class SerializedOneTimeExpenseKeyRecord(
    @SerialName("v")
    val version: Int,

    @SerialName("expense")
    val expenseUuid: String,

    val key: String,

    @SerialName("creditor")
    val creditorUuid: String,

    @SerialName("creditor_key")
    val creditorSignatureKey: String,

    @SerialName("beneficiaries")
    val beneficiaryUuids: List<String>,
)
