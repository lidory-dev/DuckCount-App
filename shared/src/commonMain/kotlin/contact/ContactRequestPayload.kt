package contact

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Unknown keys are ignored so that a field added by a newer build does not make its
 * requests unreadable to an older one.
 */
private val json = Json { ignoreUnknownKeys = true }

/**
 * What a contact request hands the recipient: the sender's profile encryption key, the
 * one thing they need to read the sender's profile and decide whether to accept.
 *
 * It travels as the plaintext of an MLS application message in the group the request
 * creates, so only the recipient can read it, and MLS authenticates who sent it.
 */
class ContactCard(
    val profileEncryptionKey: ByteArray,
) {

    /** The plaintext to encrypt for the recipient. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedContactCard.serializer(),
            SerializedContactCard(
                profileEncryptionKey = Base64.Default.encode(profileEncryptionKey),
            ),
        ).encodeToByteArray()

    companion object {
        /** Reads a card back from a decrypted message, or returns null when it is not one. */
        fun parse(plaintext: ByteArray): ContactCard? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedContactCard.serializer(),
                    plaintext.decodeToString(),
                )

                ContactCard(
                    profileEncryptionKey = Base64.Default.decode(serialized.profileEncryptionKey),
                )
            }.getOrNull()
    }
}

/**
 * The `ciphertext` of a contact request: everything the recipient needs to open it.
 *
 * The server stores one opaque string per request, while opening one takes two MLS
 * messages: the [welcome] that lets the recipient join the group the sender created for
 * the two of them, then the [message] carrying the sender's [ContactCard] inside it.
 */
class ContactRequestEnvelope(
    val welcome: ByteArray,
    val message: ByteArray,
) {

    /** The request's `ciphertext`. */
    fun serialize(): String =
        json.encodeToString(
            SerializedContactRequestEnvelope.serializer(),
            SerializedContactRequestEnvelope(
                welcome = Base64.Default.encode(welcome),
                message = Base64.Default.encode(message),
            ),
        )

    companion object {
        /** Reads an envelope back from a request's `ciphertext`, or returns null when it is not one. */
        fun parse(ciphertext: String): ContactRequestEnvelope? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedContactRequestEnvelope.serializer(),
                    ciphertext,
                )

                ContactRequestEnvelope(
                    welcome = Base64.Default.decode(serialized.welcome),
                    message = Base64.Default.decode(serialized.message),
                )
            }.getOrNull()
    }
}

@Serializable
private class SerializedContactCard(
    @SerialName("profile_encryption_key")
    val profileEncryptionKey: String,
)

@Serializable
private class SerializedContactRequestEnvelope(
    val welcome: String,
    val message: String,
)
