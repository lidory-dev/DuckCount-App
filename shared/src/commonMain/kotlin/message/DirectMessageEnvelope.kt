package message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** Unknown keys are ignored, so a field a newer build added does not make a message unreadable. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * The `ciphertext` of a direct message: an MLS message encrypted to the group the sender
 * and the recipient share.
 *
 * What travels this way is whatever the two of them have to tell each other and the server
 * must not learn: a contact card accepting a request, a notice that one removed the other,
 * the key to a one-time expense, or a request for that key. The recipient needs the group
 * to decrypt [message] and may keep no record of which one it was, and an MLS message says
 * nothing about it either, hence [mlsGroupId] next to it.
 *
 * [mlsGroupId] is chosen by the sender and authenticated by nothing, so it selects which
 * stored group to try and decides nothing else. A group that decrypts the message is the
 * proof; one that does not simply fails.
 */
class DirectMessageEnvelope(
    val mlsGroupId: ByteArray,
    val message: ByteArray,

    /**
     * The Welcome letting the recipient into [mlsGroupId], when the sender has no evidence
     * they are in it yet.
     *
     * A channel does not survive its owner logging out: the group's state lived in the
     * database the log-out wiped, and MLS offers no way back into a group you have left.
     * So the side that lost it builds a new one and has to hand the other a way in, and it
     * rides with the message rather than ahead of it because sends to one peer can reach
     * the server in either order. One that arrived before its Welcome would be unreadable,
     * acknowledged, and gone.
     *
     * Null on a channel whose peer has already been seen in the group, which is the
     * ordinary case.
     */
    val welcome: ByteArray? = null,
) {

    /** The direct message's `ciphertext`. */
    fun serialize(): String =
        json.encodeToString(
            SerializedDirectMessageEnvelope.serializer(),
            SerializedDirectMessageEnvelope(
                mlsGroupId = Base64.Default.encode(mlsGroupId),
                message = Base64.Default.encode(message),
                welcome = welcome?.let { Base64.Default.encode(it) },
            ),
        )

    companion object {
        /**
         * Reads an envelope back from a direct message's `ciphertext`, or returns null when
         * it is not one, or names a group of a size no group this device holds has.
         *
         * Every group `lib-duckcount` creates or joins has a 32-byte id. One of any other
         * size could never be read in, and the lock taken to try would still be kept for
         * the life of the process, keyed by an id whose size the sender chose.
         */
        fun parse(ciphertext: String): DirectMessageEnvelope? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedDirectMessageEnvelope.serializer(),
                    ciphertext,
                )

                DirectMessageEnvelope(
                    mlsGroupId = Base64.Default.decode(serialized.mlsGroupId),
                    message = Base64.Default.decode(serialized.message),
                    welcome = serialized.welcome?.let { Base64.Default.decode(it) },
                )
            }.getOrNull()?.takeIf { it.mlsGroupId.size == MLS_GROUP_ID_LENGTH }
    }
}

@Serializable
private class SerializedDirectMessageEnvelope(
    @SerialName("mls_group_id")
    val mlsGroupId: String,
    val message: String,
    val welcome: String? = null,
)

/** The size of every MLS group id, see [DirectMessageEnvelope.parse]. */
private const val MLS_GROUP_ID_LENGTH = 32
