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
 * The plaintext of [VaultEntryKey.CONTACTS]. Every contact, keyed by their user UUID,
 * each an object whose `profile_encryption_key` is the key they handed over and whose
 * `mls_signature_public_key` is the MLS identity this device pinned for them, both Base64
 * encoded.
 *
 * The pinned key is here because pins live in the local database, which a log-out empties,
 * while the whole point of a pin is to outlast the moment it was made. Without it, a device
 * coming back from a log-out would accept whichever key package the server served for each
 * contact and pin *that*, which is exactly the substitution pinning exists to catch.
 *
 * Follows the first of [KeyRing]'s rules for the same reason: a newer app version may
 * add a contact field, or a new kind of entry, that an older one still running
 * somewhere must not drop when it adds a contact. So this holds the [JsonObject] it was
 * read from, and adding a contact rewrites that contact's key and nothing else.
 */
class ContactBook private constructor(private val entries: JsonObject) {

    /**
     * Each contact, keyed by their user UUID.
     *
     * An entry that does not carry a Base64 profile key, which only a newer build could
     * have written, is left out: nothing here can read that contact's profile anyway. One
     * with no pinned identity is kept, since it only means this book was written before
     * pins travelled with it, or by a device that had none.
     */
    val contacts: Map<String, VaultContact>
        get() = entries.mapNotNull { (userUuid, contact) ->
            contact.key(field = PROFILE_ENCRYPTION_KEY)?.let { profileEncryptionKey ->
                userUuid to VaultContact(
                    profileEncryptionKey = profileEncryptionKey,
                    mlsSignaturePublicKey = contact.key(field = MLS_SIGNATURE_PUBLIC_KEY),
                )
            }
        }.toMap()

    /**
     * This book with [contact] as the contact [userUuid], adding them when they are not in
     * it yet.
     *
     * The other fields of a contact already in it are carried over as they were read, and
     * so is their pinned identity when [contact] carries none: a device that has not met
     * this contact over MLS knows nothing about their identity, and knowing nothing is not
     * grounds for erasing what another device recorded.
     */
    fun with(userUuid: String, contact: VaultContact): ContactBook {
        val stored = entries[userUuid] as? JsonObject ?: JsonObject(emptyMap())

        val fields = mutableMapOf<String, JsonElement>()

        fields += stored

        fields[PROFILE_ENCRYPTION_KEY] =
            JsonPrimitive(Base64.Default.encode(contact.profileEncryptionKey))

        contact.mlsSignaturePublicKey?.let {
            fields[MLS_SIGNATURE_PUBLIC_KEY] = JsonPrimitive(Base64.Default.encode(it))
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
)

private fun JsonElement.key(field: String): ByteArray? {
    val encoded = ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull
        ?: return null

    return runCatching { Base64.Default.decode(encoded) }.getOrNull()
}
