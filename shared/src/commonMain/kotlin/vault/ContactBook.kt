package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

/** The field of a contact holding the key they handed over. */
private const val PROFILE_ENCRYPTION_KEY = "profile_encryption_key"

/** The field of a contact holding the MLS signature key this device pinned for them. */
private const val MLS_SIGNATURE_PUBLIC_KEY = "mls_signature_public_key"

/**
 * The field of a contact holding the partial hash of the user's own profile key they were
 * last handed.
 */
private const val HANDED_PROFILE_KEY_HASH = "handed_profile_key_hash"

/** The field of a contact holding the safety number the user verified with them. */
private const val VERIFIED_SAFETY_NUMBER = "verified_safety_number"

/**
 * The plaintext of [VaultEntryKey.CONTACTS]. Every contact, keyed by their user UUID,
 * each an object whose `profile_encryption_key` is the key they handed over and whose
 * `mls_signature_public_key` is the MLS identity this device pinned for them, both Base64
 * encoded, whose `handed_profile_key_hash` is the partial hash of the user's own
 * profile key they were last handed, and whose `verified_safety_number` is the safety number
 * the user compared with them and marked verified.
 *
 * The pinned key is here because pins live in the local database, which a log-out empties,
 * while the whole point of a pin is to outlast the moment it was made. Without it, a device
 * coming back from a log-out would accept whichever key package the server served for each
 * contact and pin *that*, which is exactly the substitution pinning exists to catch.
 *
 * The handed hash is here because it too has to outlast a log-out: the user's profile key
 * rotates when they remove a contact, and the contacts left still to be handed the new one
 * have to be told apart from the others on a device the vault restored, see
 * [ContactEntity.handedProfileKeyHash][database.data.local.entity.ContactEntity.handedProfileKeyHash].
 *
 * The verified safety number is here for the same reason: the user made the mark by comparing
 * numbers with the contact in person or on a call, and a log-out or a reinstall must not have
 * them do it again, see
 * [ContactEntity.verifiedSafetyNumber][database.data.local.entity.ContactEntity.verifiedSafetyNumber].
 *
 * Follows the first of [KeyRing]'s rules for the same reason: a newer app version may
 * add a contact field, or a new kind of entry, that an older one still running
 * somewhere must not drop when it adds a contact. So this holds the [JsonObject] it was
 * read from, and adding a contact rewrites the fields of that contact this build knows,
 * and nothing else.
 */
class ContactBook private constructor(private val entries: JsonObject) {

    /**
     * Each contact, keyed by their user UUID.
     *
     * An entry that does not carry a Base64 profile key, which only a newer build could
     * have written, is left out: nothing here can read that contact's profile anyway. One
     * with no pinned identity is kept, since it only means this book was written before
     * pins travelled with it, or by a device that had none. So is one with no handed hash,
     * which only means nobody knows which of the user's keys they hold, and one with no
     * verified number, which only means the user has not verified them.
     */
    val contacts: Map<String, VaultContact>
        get() = entries.mapNotNull { (userUuid, contact) ->
            contact.key(field = PROFILE_ENCRYPTION_KEY)?.let { profileEncryptionKey ->
                userUuid to VaultContact(
                    profileEncryptionKey = profileEncryptionKey,
                    mlsSignaturePublicKey = contact.key(field = MLS_SIGNATURE_PUBLIC_KEY),
                    handedProfileKeyHash = contact.text(field = HANDED_PROFILE_KEY_HASH),
                    verifiedSafetyNumber = contact.text(field = VERIFIED_SAFETY_NUMBER),
                )
            }
        }.toMap()

    /**
     * This book with [contact] as the contact [userUuid], adding them when they are not in
     * it yet, or null when it pins another identity for them than [contact] does.
     *
     * The other fields of a contact already in it are carried over as they were read, and
     * so is their pinned identity when [contact] carries none: a device that has not met
     * this contact over MLS knows nothing about their identity, and knowing nothing is not
     * grounds for erasing what another device recorded. The same goes for the handed hash.
     *
     * Not for the verified safety number, which is written as [contact] carries it: none is the
     * user clearing the mark, or a removal having ended it, see
     * [ContactDao.add][database.data.local.dao.ContactDao.add], not a device that knows nothing
     * about it, since the mark comes back from the vault along with the contact, see
     * [ContactRepository.restore][database.data.repository.ContactRepository.restore].
     *
     * A pinned identity is never replaced. No flow in the app changes one, so one that
     * differs was most likely served by somebody other than the contact, to whichever device
     * pinned it, and writing it here would hand it to every device that restores from the
     * book.
     */
    fun with(userUuid: String, contact: VaultContact): ContactBook? {
        val stored = entries[userUuid] as? JsonObject ?: JsonObject(emptyMap())

        val storedPin = stored.key(field = MLS_SIGNATURE_PUBLIC_KEY)
        val pin = contact.mlsSignaturePublicKey

        if (storedPin != null && pin != null && !storedPin.contentEquals(pin)) return null

        val fields = mutableMapOf<String, JsonElement>()

        fields += stored

        fields[PROFILE_ENCRYPTION_KEY] =
            JsonPrimitive(Base64.Default.encode(contact.profileEncryptionKey))

        if (storedPin == null && pin != null) {
            fields[MLS_SIGNATURE_PUBLIC_KEY] = JsonPrimitive(Base64.Default.encode(pin))
        }

        contact.handedProfileKeyHash?.let { fields[HANDED_PROFILE_KEY_HASH] = JsonPrimitive(it) }

        when (val verifiedSafetyNumber = contact.verifiedSafetyNumber) {
            null -> fields -= VERIFIED_SAFETY_NUMBER
            else -> fields[VERIFIED_SAFETY_NUMBER] = JsonPrimitive(verifiedSafetyNumber)
        }

        return ContactBook(JsonObject(entries + (userUuid to JsonObject(fields))))
    }

    /**
     * This book without the contact [userUuid], along with every field they carried,
     * including the ones this build does not know: they described somebody who is no
     * longer a contact.
     */
    fun without(userUuid: String): ContactBook =
        ContactBook(JsonObject(entries - userUuid))

    /** The bytes to seal under the key ring. */
    fun serialize(): ByteArray =
        Json.encodeToString(JsonObject.serializer(), entries).encodeToByteArray()

    companion object {
        /** The book of a user who has never had a contact. */
        val EMPTY = ContactBook(JsonObject(emptyMap()))

        /**
         * Reads a contact book back from the plaintext of [VaultEntryKey.CONTACTS], or
         * returns null when those bytes are not a JSON object.
         */
        fun parse(plaintext: ByteArray): ContactBook? =
            runCatching {
                ContactBook(Json.decodeFromString(JsonObject.serializer(), plaintext.decodeToString()))
            }.getOrNull()
    }
}

/**
 * One contact as the vault holds them.
 *
 * [mlsSignaturePublicKey] is null for a contact recorded before pins travelled with the
 * book, or by a device that never reached them over MLS.
 */
class VaultContact(
    val profileEncryptionKey: ByteArray,
    val mlsSignaturePublicKey: ByteArray?,

    /**
     * See [ContactEntity.handedProfileKeyHash][database.data.local.entity.ContactEntity.handedProfileKeyHash].
     * Null when which key they were handed is not known.
     */
    val handedProfileKeyHash: String?,

    /**
     * See [ContactEntity.verifiedSafetyNumber][database.data.local.entity.ContactEntity.verifiedSafetyNumber].
     * Null when the user has not verified them, or cleared the mark.
     */
    val verifiedSafetyNumber: String?,
)

private fun JsonElement.key(field: String): ByteArray? {
    val encoded = ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull
        ?: return null

    return runCatching { Base64.Default.decode(encoded) }.getOrNull()
}

private fun JsonElement.text(field: String): String? =
    ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
