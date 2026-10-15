package expense

import database.data.local.entity.OneTimeExpenseSettledMarkEntity
import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.OneTimeExpenseSettledMark
import uniffi.lib_duckcount.verifyOneTimeExpenseSettledMark
import vault.sealBound
import vault.unsealBound
import kotlin.io.encoding.Base64

private val json = Json { ignoreUnknownKeys = true }

/** What a mark is sealed bound to, so that it opens as nothing else of its expense. */
private const val SETTLED_CONTEXT = "one_time_expense:settled"

/**
 * The creditor's mark saying whether a one-time expense was paid back, opened and checked.
 *
 * Apart from the expense's versions: marking it is no edit, may happen any number of times, and
 * leaves them as they are. So it is signed apart too, by the creditor, with their MLS identity
 * key and a label of its own, over the expense's UUID, [markedAt] and [isSettled], see
 * `one_time_expense` in lib-duckcount. Sealed under the expense's key, it is what the server
 * lists as `is_settled`.
 */
internal class SettledMark(
    val expenseUuid: String,

    /**
     * When the creditor made it, in milliseconds since the epoch, which orders marks: see
     * [nextSettledMarkTime] for how it is chosen, and [OneTimeExpenseSettledMarkEntity] for how
     * an older one is refused.
     */
    val markedAt: Long,

    val isSettled: Boolean,
) {
    fun toEntity() = OneTimeExpenseSettledMarkEntity(
        expenseUuid = expenseUuid,
        markedAt = markedAt,
        isSettled = isSettled,
    )
}

/**
 * When a new mark is said to be made: [now], unless that is no later than [newest], the newest
 * mark this device has shown, whose time it then follows by a millisecond.
 *
 * The clock alone orders marks across log-outs, which leave a device nothing to count from.
 * But it can be wrong: a creditor whose clock ran ahead once and was put right would make
 * marks that look older than their own, and every participant would refuse them. Following
 * the newest shown is what keeps every new mark newer than that, whatever the clock says.
 *
 * Only a mark shown counts. One the server took without the answer reaching this device is
 * not, so a clock put back before the next mark can still date that one earlier, and a
 * participant who was shown the first refuses it until a later mark. That takes a lost answer
 * and a clock stepping back between two marks, and is left at that.
 */
internal fun nextSettledMarkTime(now: Long, newest: Long?): Long =
    if (newest == null || now > newest) now else newest + 1

/**
 * Signs, as [client]'s user, the mark saying whether the expense [record] opens was paid back,
 * and seals it under its key, ready for `PUT /expenses/{uuid}/settled`.
 *
 * Returns null only when signing fails or [record]'s key is not a 32-byte key, both bugs rather
 * than states, see [sealOneTime].
 */
internal fun sealSettledMark(
    record: OneTimeExpenseKeyRecord,
    markedAt: Long,
    isSettled: Boolean,
    client: MlsClient,
): EncryptedFieldDto? {
    val signature = try {
        client.signOneTimeExpenseSettledMark(
            mark = OneTimeExpenseSettledMark(
                expenseUuid = record.expenseUuid,
                markedAt = markedAt.toULong(),
                isSettled = isSettled,
            ),
        )
    } catch (_: MlsException) {
        return null
    }

    return sealBound(
        key = record.key,
        context = SETTLED_CONTEXT,
        plaintext = SignedSettledMark(markedAt = markedAt, isSettled = isSettled, signature = signature).serialize(),
    )
}

/**
 * The creditor's mark on this expense, or null when it carries none, or one that does not open
 * under [record]'s key, or that [record]'s creditor did not sign for this very expense with the
 * key [record] holds them to, see [unsealOneTime].
 *
 * Only the mark is checked here, not whether it is the newest this device has seen: a server
 * can serve a genuine mark from before the newest, which storing it refuses, see
 * `ExpenseDao.storeSettledMarks`.
 */
internal fun ExpenseDto.openSettledMark(record: OneTimeExpenseKeyRecord): SettledMark? {
    val sealed = is_settled ?: return null

    val signed = unsealBound(key = record.key, context = SETTLED_CONTEXT, content = sealed)
        ?.let { SignedSettledMark.parse(plaintext = it) }
        ?: return null

    val isSignedByCreditor = verifyOneTimeExpenseSettledMark(
        creditorUuid = record.creditorUuid,
        creditorSignatureKey = record.creditorSignatureKey,
        mark = OneTimeExpenseSettledMark(
            expenseUuid = uuid,
            markedAt = signed.markedAt.toULong(),
            isSettled = signed.isSettled,
        ),
        signature = signed.signature,
    )

    if (!isSignedByCreditor) return null

    return SettledMark(expenseUuid = uuid, markedAt = signed.markedAt, isSettled = signed.isSettled)
}

/** A mark and its creditor's signature over it: what `is_settled` holds, sealed. */
private class SignedSettledMark(val markedAt: Long, val isSettled: Boolean, val signature: ByteArray) {

    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedSignedSettledMark.serializer(),
            SerializedSignedSettledMark(
                markedAt = markedAt,
                isSettled = if (isSettled) 1 else 0,
                signature = Base64.Default.encode(signature),
            ),
        ).encodeToByteArray()

    companion object {
        fun parse(plaintext: ByteArray): SignedSettledMark? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedSignedSettledMark.serializer(),
                    plaintext.decodeToString(),
                )

                SignedSettledMark(
                    markedAt = serialized.markedAt,
                    isSettled = when (serialized.isSettled) {
                        1 -> true
                        0 -> false
                        else -> return null
                    },
                    signature = Base64.Default.decode(serialized.signature),
                )
            }.getOrNull()
    }
}

@Serializable
private class SerializedSignedSettledMark(
    @SerialName("marked_at")
    val markedAt: Long,

    /**
     * 1 when settled, 0 when not, and not `true` or `false`, which differ in length: AES-GCM
     * does not hide how long what it seals is, and the server would tell a settled expense
     * from one that is not by the length of its mark.
     */
    @SerialName("is_settled")
    val isSettled: Int,

    val signature: String,
)
