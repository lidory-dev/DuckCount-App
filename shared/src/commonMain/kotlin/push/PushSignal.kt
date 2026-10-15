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
 * What a push signal says has arrived, and who sent it.
 *
 * The whole payload of a push, which is deliberately this small: the server is blind
 * to the message the signal is about, so it has nothing else it could say. Who sent it,
 * it does know, and says so for this device to refuse a signal from anybody who may not
 * wake the user, see [mayWake]. That gives nothing away: the payload is encrypted to the
 * device, so the push service reads none of it.
 */
data class PushSignal(
    val kind: Kind,

    /**
     * The user whose action sent it: the sender of the message, whoever asked to become a
     * contact, the creditor who changed an expense. Null when the payload names nobody this
     * build can read, which is taken as nobody at all.
     */
    val from: String?,
) {
    enum class Kind {

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
         * Still worth syncing on, from somebody who may wake the user. A signal only says
         * that something arrived, and a client that ignored the ones it does not recognize
         * would go quiet the moment the server learned a new word. A payload it could not
         * read names nobody, so that one goes no further.
         */
        UNKNOWN,
    }

    /**
     * Whether this signal may wake the user, by who sent it.
     *
     * A request to become a contact comes from a stranger by definition, so it is taken from
     * anybody. Everything else only from a contact, see [WakeList], or, for a [Kind.MESSAGE],
     * from somebody the user sent a contact request to in the last week: that is how their
     * acceptance arrives, and they are a contact only once the sync has read it. A signal
     * naming nobody is from nobody the user knows.
     */
    fun mayWake(wakeList: WakeListStore): Boolean {
        if (kind == Kind.CONTACT_REQUEST) return true

        val from = from ?: return false

        return wakeList.isContact(userUuid = from) ||
            kind == Kind.MESSAGE && wakeList.isRequested(userUuid = from)
    }

    companion object {
        /** Reads the signal a push carried. */
        fun parse(payload: ByteArray): PushSignal {
            val serialized = runCatching {
                json.decodeFromString<SerializedPushSignal>(payload.decodeToString())
            }.getOrNull() ?: return PushSignal(kind = Kind.UNKNOWN, from = null)

            val kind = when (serialized.type) {
                "contact_request" -> Kind.CONTACT_REQUEST
                "message" -> Kind.MESSAGE
                "one_time_expense" -> Kind.ONE_TIME_EXPENSE
                "one_time_expense_changed" -> Kind.ONE_TIME_EXPENSE_CHANGED
                else -> Kind.UNKNOWN
            }

            return PushSignal(kind = kind, from = serialized.from)
        }
    }
}

@Serializable
private data class SerializedPushSignal(
    @SerialName("type") val type: String,
    @SerialName("from") val from: String? = null,
)
