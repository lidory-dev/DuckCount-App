package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

/**
 * The plaintext of [VaultEntryKey.KEY_RING]. One key per part of the vault, Base64
 * encoded, keyed by the part's wire name.
 *
 * It maps each part to its own key rather than deriving them all from the root key,
 * so that a key which is meant to leave the vault can:
 * [VaultEntryKey.PROFILE_ENCRYPTION_KEY]'s is handed to contacts, and giving it away
 * must say nothing about the keys of the other parts.
 *
 * Two rules protect a key that a newer app version added from an older one still
 * running on another device, and the server can enforce neither:
 *
 * 1. **Re-seal it by round-tripping the plaintext as a map**, which is why this holds
 *    a [JsonObject] rather than a data class with a field per part. An entry this
 *    build has never heard of is carried through with untouched instead of being
 *    dropped on the next write.
 * 2. **Write it with an expected version**, so a key another device added between the
 *    read and the write is refused rather than lost. That is [VaultRepository]'s job.
 */
class KeyRing private constructor(private val entries: JsonObject) {

    /**
     * The key sealing [part], or null when the ring does not carry one yet.
     *
     * Also null when the entry is there but is not a Base64 string, which a newer
     * build could have written, unreadable and absent are the same answer here, since
     * neither is something this build can seal anything with.
     */
    fun keyFor(part: VaultEntryKey): ByteArray? {
        val encoded = (entries[part.wireName] as? JsonPrimitive)?.contentOrNull ?: return null

        return runCatching { Base64.Default.decode(encoded) }.getOrNull()
    }

    /**
     * This key ring with [key] sealing [part], replacing any key it already held for it.
     *
     * Every other entry is carried over as it was read, including the ones this build
     * does not recognize.
     */
    fun with(part: VaultEntryKey, key: ByteArray): KeyRing =
        KeyRing(
            JsonObject(entries + (part.wireName to JsonPrimitive(Base64.Default.encode(key)))),
        )

    /** The bytes to seal under the root key. */
    fun serialize(): ByteArray =
        Json.encodeToString(JsonObject.serializer(), entries).encodeToByteArray()

    companion object {
        /** The key ring of a vault that has never been set up. */
        val EMPTY = KeyRing(JsonObject(emptyMap()))

        /**
         * Reads a key ring back from the plaintext of [VaultEntryKey.KEY_RING], or returns
         * null when those bytes are not a JSON object.
         */
        fun parse(plaintext: ByteArray): KeyRing? =
            runCatching {
                KeyRing(Json.decodeFromString(JsonObject.serializer(), plaintext.decodeToString()))
            }.getOrNull()
    }
}
