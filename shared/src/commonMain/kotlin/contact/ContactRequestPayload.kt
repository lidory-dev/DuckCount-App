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
 * one thing they need to read the sender's profile and decide whether to accept. Accepting
 * one hands the sender a card in turn, carrying the recipient's.
 *
 * It travels as the plaintext of an MLS application message in the group the request
 * creates, so only the recipient can read it, and MLS authenticates who sent it.
 *
 * The key it carries is the one current when it went out. One that replaces it later
 * travels as a [ProfileKeyUpdate].
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

/**
 * What a contact sends the other when removing them from their contacts: nothing but the
 * fact of it. Deleting their account sends an [AccountDeletionNotice] instead.
 *
 * It travels as the plaintext of an MLS application message in the group the two of them
 * share, so MLS authenticates who is leaving. Its `type` is what tells it apart from a
 * [ContactCard], which travels the same way.
 */
object ContactRemoval {
    private const val TYPE = "contact_removal"

    /** The plaintext to encrypt for the contact being removed. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedContactRemoval.serializer(),
            SerializedContactRemoval(type = TYPE),
        ).encodeToByteArray()

    /** Whether a decrypted message is a removal. */
    fun matches(plaintext: ByteArray): Boolean =
        runCatching {
            json.decodeFromString(
                SerializedContactRemoval.serializer(),
                plaintext.decodeToString(),
            ).type == TYPE
        }.getOrDefault(false)
}

/**
 * What the user's app sends each contact as the user deletes their account, in place of a
 * [ContactRemoval]: nothing but the fact of it, see [account.AccountDeletion].
 *
 * The contact's app forgets the user as it would on a removal, keeping nothing of them in the
 * vault but the name it shows for them, as for any former contact, see
 * [ContactRepository.receiveAccountDeletion][database.data.repository.ContactRepository.receiveAccountDeletion]:
 * the server deletes the profile with the account, so nothing else could tell their devices who
 * the user was in the expenses the two of them share once one of them lost its data.
 *
 * It travels as a removal does, so MLS authenticates who is leaving, and only a contact's counts.
 * Its `type` tells it apart from a removal, and carrying no key, it is never taken for a
 * [ContactCard], see [ContactMessage.read].
 */
object AccountDeletionNotice {
    private const val TYPE = "account_deletion"

    /** The plaintext to encrypt for each contact. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedAccountDeletionNotice.serializer(),
            SerializedAccountDeletionNotice(type = TYPE),
        ).encodeToByteArray()

    /** Whether a decrypted message is a notice of account deletion. */
    fun matches(plaintext: ByteArray): Boolean =
        runCatching {
            json.decodeFromString(
                SerializedAccountDeletionNotice.serializer(),
                plaintext.decodeToString(),
            ).type == TYPE
        }.getOrDefault(false)
}

/**
 * What the user sends a contact when the key to their profile has changed: the new key,
 * which reads the profile from then on.
 *
 * The key rotates when the user removes a contact, so the one removed, who holds the old
 * key, reads nothing the user writes after, see `ProfileRepository.rotateProfileKey`, and
 * every contact left has to be handed the new one, see [ProfileKeyDelivery]. It is also the
 * answer to a contact whose copy went missing, and who asked for it, see [ProfileKeyRequest].
 *
 * It travels as the plaintext of an MLS application message in the group the two of them
 * share, so MLS authenticates whose key it is. Its `type` tells it apart from a
 * [ContactCard], which carries a key under the same name but no `type`, being recognised by
 * its shape: an update has to be recognised first, see [ContactMessage.read].
 */
class ProfileKeyUpdate(
    val profileEncryptionKey: ByteArray,
) {

    /** The plaintext to encrypt for the contact. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedProfileKeyUpdate.serializer(),
            SerializedProfileKeyUpdate(
                type = TYPE,
                profileEncryptionKey = Base64.Default.encode(profileEncryptionKey),
            ),
        ).encodeToByteArray()

    companion object {
        private const val TYPE = "profile_key"

        /** Reads an update back from a decrypted message, or returns null when it is not one. */
        fun parse(plaintext: ByteArray): ProfileKeyUpdate? =
            runCatching {
                val serialized = json.decodeFromString(
                    SerializedProfileKeyUpdate.serializer(),
                    plaintext.decodeToString(),
                )

                serialized.takeIf { it.type == TYPE }?.let {
                    ProfileKeyUpdate(profileEncryptionKey = Base64.Default.decode(it.profileEncryptionKey))
                }
            }.getOrNull()
    }
}

/**
 * What the user sends a contact to ask for the key to their profile, when the one this device
 * holds no longer opens their latest version: nothing but the fact of it, see [ProfileKeyPull].
 *
 * It travels as the plaintext of an MLS application message, so MLS authenticates who is
 * asking, and the contact answers only somebody who is one of theirs, in the group the request
 * was read in, with a [ProfileKeyUpdate]. Its `type` tells it apart from a [ContactCard] and
 * the rest, and it is recognised before a card is, as an update is, see [ContactMessage.read].
 */
object ProfileKeyRequest {
    private const val TYPE = "profile_key_request"

    /** The plaintext to encrypt for the contact asked. */
    fun serialize(): ByteArray =
        json.encodeToString(
            SerializedProfileKeyRequest.serializer(),
            SerializedProfileKeyRequest(type = TYPE),
        ).encodeToByteArray()

    /** Whether a decrypted message is a request for the user's profile key. */
    fun matches(plaintext: ByteArray): Boolean =
        runCatching {
            json.decodeFromString(
                SerializedProfileKeyRequest.serializer(),
                plaintext.decodeToString(),
            ).type == TYPE
        }.getOrDefault(false)
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

@Serializable
private class SerializedContactRemoval(
    val type: String,
)

@Serializable
private class SerializedAccountDeletionNotice(
    val type: String,
)

@Serializable
private class SerializedProfileKeyUpdate(
    val type: String,

    @SerialName("profile_encryption_key")
    val profileEncryptionKey: String,
)

@Serializable
private class SerializedProfileKeyRequest(
    val type: String,
)
