package push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unknown keys are ignored so that a field added by a newer server does not make a
 * signal unreadable to an older client.
 */
private val json = Json { ignoreUnknownKeys = true }

/**
 * What a push signal says has arrived.
 *
 * The whole payload of a push, which is deliberately this small: the server is blind
 * to the message the signal is about, so it has nothing else it could say.
 */
enum class PushSignal {

    /** Somebody asked to become a contact. */
    CONTACT_REQUEST,

    /**
     * A direct message is waiting. An acceptance and a notice that a contact removed
     * the user both arrive as this: the server cannot tell them apart, only the
     * client can, once it has decrypted the message itself.
     */
    MESSAGE,

    /**
     * A direct message is waiting that carries the key to a one-time expense, which
     * is what makes that expense readable.
     *
     * The one kind of direct message the server is told anything about, and it is
     * told so that a locked device has something true to say. Under [MESSAGE] it has
     * none: the same signal covers a contact removing the user, and a device that
     * cannot read its own storage cannot tell which arrived.
     */
    ONE_TIME_EXPENSE,

    /**
     * The creditor of a one-time expense the user is on marked it as settled or not
     * settled, edited it, or deleted it or took the user off it.
     *
     * One signal for all of them, and it says no more than that: which expenses changed,
     * and how, the device learns only from its sync. The server could not say which way a
     * mark went anyway, what it says being sealed under the expense's key.
     */
    ONE_TIME_EXPENSE_CHANGED,

    /**
     * Something this build does not know about, including a payload it could not
     * read at all.
     *
     * Still worth syncing on. A signal only says that something arrived, and a
     * client that ignored the ones it does not recognize would go quiet the moment
     * the server learned a new word.
     */
    UNKNOWN,
    ;

    companion object {
        /** Reads the signal a push carried. */
        fun parse(payload: ByteArray): PushSignal {
            val serialized = runCatching {
                json.decodeFromString<SerializedPushSignal>(payload.decodeToString())
            }.getOrNull() ?: return UNKNOWN

            return when (serialized.type) {
                "contact_request" -> CONTACT_REQUEST
                "message" -> MESSAGE
                "one_time_expense" -> ONE_TIME_EXPENSE
                "one_time_expense_changed" -> ONE_TIME_EXPENSE_CHANGED
                else -> UNKNOWN
            }
        }
    }
}

@Serializable
private data class SerializedPushSignal(
    @SerialName("type") val type: String,
)
