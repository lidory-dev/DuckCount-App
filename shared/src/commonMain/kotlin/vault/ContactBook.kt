package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

/** The field of a contact holding the key they handed over. */
private const val PROFILE_ENCRYPTION_KEY = "profile_encryption_key"

/**
 * The plaintext of [VaultEntryKey.CONTACTS]. Every contact, keyed by their user UUID,
 * each an object whose `profile_encryption_key` is the key they handed over, Base64
 * encoded.
 *
 * Follows the first of [KeyRing]'s rules for the same reason: a newer app version may
 * add a contact field, or a new kind of entry, that an older one still running
 * somewhere must not drop when it adds a contact. So this holds the [JsonObject] it was
 * read from, and adding a contact rewrites that contact's key and nothing else.
 */
class ContactBook private constructor(private val entries: JsonObject) {

    /**
     * The key each contact handed over, keyed by their user UUID.
     *
     * An entry that does not carry a Base64 key, which only a newer build could have
     * written, is left out: nothing here can read that contact's profile anyway.
     */
    val profileEncryptionKeys: Map<String, ByteArray>
        get() = entries.mapNotNull { (userUuid, contact) ->
            contact.profileEncryptionKey()?.let { key -> userUuid to key }
        }.toMap()

    /**
     * This book with [profileEncryptionKey] as the key of the contact [userUuid],
     * adding them when they are not in it yet.
     *
     * The other fields of a contact already in it are carried over as they were read.
     */
    fun with(userUuid: String, profileEncryptionKey: ByteArray): ContactBook {
        val contact = entries[userUuid] as? JsonObject ?: JsonObject(emptyMap())

        val updated = JsonObject(
            contact + (PROFILE_ENCRYPTION_KEY to JsonPrimitive(Base64.Default.encode(profileEncryptionKey))),
        )

        return ContactBook(JsonObject(entries + (userUuid to updated)))
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

private fun JsonElement.profileEncryptionKey(): ByteArray? {
    val encoded = ((this as? JsonObject)?.get(PROFILE_ENCRYPTION_KEY) as? JsonPrimitive)
        ?.contentOrNull
        ?: return null

    return runCatching { Base64.Default.decode(encoded) }.getOrNull()
}
