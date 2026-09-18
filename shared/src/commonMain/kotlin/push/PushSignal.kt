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
                else -> UNKNOWN
            }
        }
    }
}

@Serializable
private data class SerializedPushSignal(
    @SerialName("type") val type: String,
)
