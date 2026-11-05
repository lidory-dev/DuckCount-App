package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The field of a blocked user holding the name the user saw them by as they blocked them. */
private const val FULL_NAME = "full_name"

/**
 * The plaintext of [VaultEntryKey.BLOCKED_USERS]: everybody the user blocked, keyed by their user
 * UUID, each an object whose `full_name` is the name the user saw them by as they blocked them, when
 * there was one, see [BlockedUserEntity][database.data.local.entity.BlockedUserEntity].
 *
 * Here because a block is the user's word, which has to outlast the device it was given on: a
 * device that lost its data, to a log-out, a reinstall or a new phone, would otherwise take in the
 * very next request of somebody the user blocked, and the server keeps no record of blocks to stop
 * it. The name is here because nothing else is left to name a blocked user by on such a device:
 * one who sent a request was never a contact, and the request goes as it is refused.
 *
 * Sealed whole and padded, see [paddedToBookSize], so that the server learns neither who the user
 * blocked nor how many.
 *
 * Follows the first of [KeyRing]'s rules, as [ContactBook] does: a newer app version may add a
 * field to an entry, or entries of a new kind, that an older one still running somewhere must not
 * drop when it blocks somebody. So this holds the [JsonObject] it was read from, and blocking
 * somebody rewrites the one field of theirs this build knows, and nothing else.
 */
class BlockList private constructor(private val entries: JsonObject) {

    /**
     * Everybody the user blocked, keyed by their user UUID, with the name they were blocked under,
     * or null when there was none, or it is not text, which only a newer build could have written.
     */
    val blocked: Map<String, String?>
        get() = entries.mapValues { (_, entry) -> entry.text(field = FULL_NAME) }

    /**
     * This list with what a backup carries, see [VaultRepository.updateBlockList]: everybody in
     * [blocked] blocked under the name it maps them to, and everybody in [unblocked] gone, along with
     * every field they carried, the ones this build does not know included.
     *
     * A name of none leaves the one the list holds for them, if any: blocking them again with no
     * name to show is no reason to forget the one another device had.
     */
    fun withChanges(blocked: Map<String, String?>, unblocked: Set<String>): BlockList {
        val updated = entries.toMutableMap()

        blocked.forEach { (userUuid, fullName) ->
            val fields = (updated[userUuid] as? JsonObject).orEmpty().toMutableMap()

            fullName?.let { fields[FULL_NAME] = JsonPrimitive(it) }

            updated[userUuid] = JsonObject(fields)
        }

        unblocked.forEach { updated -= it }

        return BlockList(JsonObject(updated))
    }

    /** The bytes to seal under the key ring, padded so that their length says little of how many there are, see [paddedToBookSize]. */
    fun serialize(): ByteArray =
        Json.encodeToString(JsonObject.serializer(), entries).encodeToByteArray().paddedToBookSize()

    companion object {
        /** The list of a user who never blocked anybody. */
        val EMPTY = BlockList(JsonObject(emptyMap()))

        /**
         * Reads a block list back from the plaintext of [VaultEntryKey.BLOCKED_USERS], or returns
         * null when those bytes are not a JSON object. At whatever size it comes, as a contact book
         * is, see [paddedToBookSize]: only the user's own devices write it.
         */
        fun parse(plaintext: ByteArray): BlockList? =
            runCatching {
                BlockList(Json.decodeFromString(JsonObject.serializer(), plaintext.decodeToString()))
            }.getOrNull()
    }
}

private fun JsonElement.text(field: String): String? =
    ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
