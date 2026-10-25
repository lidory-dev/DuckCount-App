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
     * contact or accepted, the creditor who changed an expense, the creator of a group. Null
     * when the payload names nobody this build can read, which is taken as nobody at all.
     */
    val from: String?,
) {
    enum class Kind {

        /** Somebody asked to become a contact. */
        CONTACT_REQUEST,

        /**
         * Somebody the user asked to become a contact accepted.
         *
         * Sent by the server as it takes the acceptance, and by nothing else: no client can
         * have it sent the way it has a message sent, which is what lets this device take it
         * from somebody who is not a contact yet, see [mayWake]. Once per request at most,
         * accepting one consuming it and refusing one deleting it. They become a contact only
         * once the sync has read the card the acceptance carries.
         */
        CONTACT_ACCEPTED,

        /**
         * A direct message is waiting. A notice that a contact removed the user arrives as
         * this: the server cannot tell it from any other message, only the client can, once
         * it has decrypted the message itself.
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
         * A contact created a group with the user in it, whose Welcome is waiting for them, see
         * `group.GroupSync`.
         *
         * Taken only from a contact, like a message: the server lets anybody create a group with
         * anybody in it, and a stranger's group is one the sync declines without a word.
         */
        GROUP_WELCOME,

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
     * Whether this signal may wake the user, by who sent it, see [WakeList].
     *
     * A request to become a contact comes from a stranger by definition, so it is taken from
     * anybody. An acceptance only from somebody the user sent a contact request still waiting
     * for its answer, since they are a contact only once the sync has read it. Everything
     * else only from a contact. A signal naming nobody is from nobody the user knows.
     */
    fun mayWake(wakeList: WakeListStore): Boolean {
        if (kind == Kind.CONTACT_REQUEST) return true

        val from = from ?: return false

        return when (kind) {
            Kind.CONTACT_ACCEPTED -> wakeList.isRequested(userUuid = from)
            else -> wakeList.isContact(userUuid = from)
        }
    }

    companion object {
        /** Reads the signal a push carried. */
        fun parse(payload: ByteArray): PushSignal {
            val serialized = runCatching {
                json.decodeFromString<SerializedPushSignal>(payload.decodeToString())
            }.getOrNull() ?: return PushSignal(kind = Kind.UNKNOWN, from = null)

            val kind = when (serialized.type) {
                "contact_request" -> Kind.CONTACT_REQUEST
                "contact_accepted" -> Kind.CONTACT_ACCEPTED
                "message" -> Kind.MESSAGE
                "one_time_expense" -> Kind.ONE_TIME_EXPENSE
                "one_time_expense_changed" -> Kind.ONE_TIME_EXPENSE_CHANGED
                "group_welcome" -> Kind.GROUP_WELCOME
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
