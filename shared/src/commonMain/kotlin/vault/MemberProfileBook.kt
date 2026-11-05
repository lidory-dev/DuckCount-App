package vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

/** The field of a member holding the key to their profile. */
private const val PROFILE_ENCRYPTION_KEY = "profile_encryption_key"

/** The field of a member saying whether that key came from its owner. */
private const val FROM_OWNER = "from_owner"

/** The field of a member holding the name read with it. */
private const val FULL_NAME = "full_name"

/**
 * The plaintext of [VaultEntryKey.MEMBER_PROFILE_KEYS]. Every member of the user's groups this
 * device learned the profile key of through a group, keyed by their user UUID, each an object whose
 * `profile_encryption_key` is that key, Base64 encoded, whose `from_owner` says whether it came from
 * its owner rather than relayed by the creator of a group, and whose `full_name`, when there is one,
 * is the name read with it, see
 * [MemberProfileEntity][database.data.local.entity.MemberProfileEntity].
 *
 * The name is here because the key may no longer open anything by the time a device comes back
 * from a log-out: a member who deleted their account takes every version of their profile with
 * it, as does one whose key rotated ten times since, and the name would be lost with it.
 *
 * It does not say when a key from its owner came in, which a device ranks two of theirs by, see
 * [MemberProfileEntity.ownerKeySentAt][database.data.local.entity.MemberProfileEntity.ownerKeySentAt]:
 * the book is read back only after a log-in, onto a device holding no MLS state, and no message
 * that device reads from then on is older than those the book's keys came in.
 *
 * Merged rather than overwritten, see [with], for the same reason the contacts are: another device
 * of the user's may have written a member this one never met, or a better key for one it did.
 *
 * Follows the first of [KeyRing]'s rules, as the contacts do, see [ContactBook]: a newer app
 * version may add a member field, or a new kind of entry, that an older one still running
 * somewhere must not drop. So this holds the [JsonObject] it was read from, and writing a member
 * rewrites the fields of that member this build knows, and nothing else.
 */
class MemberProfileBook private constructor(private val entries: JsonObject) {

    /**
     * Each member, keyed by their user UUID.
     *
     * An entry that does not carry a Base64 profile key is left out, which only a newer build
     * could have written: nothing here can read that member's profile anyway. One with no owner
     * flag is taken for a relayed key, the weaker of the two, and one whose name is not text has
     * none.
     */
    val members: Map<String, VaultMemberProfile>
        get() = entries.mapNotNull { (userUuid, entry) ->
            entry.key()?.let { profileEncryptionKey ->
                userUuid to VaultMemberProfile(
                    profileEncryptionKey = profileEncryptionKey,
                    isFromOwner = entry.isFromOwner(),
                    fullName = entry.fullName(),
                )
            }
        }.toMap()

    /**
     * This book with [member] as the member [userUuid], adding them when they are not in it yet.
     *
     * A key from its owner is never replaced by a relayed one, which the creator of a group may
     * have held since before its owner replaced it: the book stays as it is. Any other key replaces
     * the one the book holds: from a source as good, the one this device holds now is the later.
     *
     * The name goes with the key. One [member] carries none of is left as the book held it: a
     * device that read no name knows nothing about it, and knowing nothing is not grounds for
     * erasing what another device read. The other fields of a member already in it are carried
     * over as they were read.
     */
    fun with(userUuid: String, member: VaultMemberProfile): MemberProfileBook {
        val stored = entries[userUuid] as? JsonObject ?: JsonObject(emptyMap())

        if (stored.key() != null && stored.isFromOwner() && !member.isFromOwner) return this

        val fields = mutableMapOf<String, JsonElement>()

        fields += stored

        fields[PROFILE_ENCRYPTION_KEY] = JsonPrimitive(Base64.Default.encode(member.profileEncryptionKey))
        fields[FROM_OWNER] = JsonPrimitive(member.isFromOwner)

        member.fullName?.let { fields[FULL_NAME] = JsonPrimitive(it) }

        return MemberProfileBook(JsonObject(entries + (userUuid to JsonObject(fields))))
    }

    /** This book with each member in [added] written, see [with]. */
    fun withChanges(added: Map<String, VaultMemberProfile>): MemberProfileBook =
        added.entries.fold(this) { book, (userUuid, member) -> book.with(userUuid = userUuid, member = member) }

    /** The bytes to seal under the key ring, padded so that their length says little of how many there are, see [paddedToBookSize]. */
    fun serialize(): ByteArray =
        Json.encodeToString(JsonObject.serializer(), entries).encodeToByteArray().paddedToBookSize()

    companion object {
        /** The book of a user who never learned a member's key from a group. */
        val EMPTY = MemberProfileBook(JsonObject(emptyMap()))

        /**
         * Reads a book back from the plaintext of [VaultEntryKey.MEMBER_PROFILE_KEYS], or returns
         * null when those bytes are not a JSON object. At whatever size it comes, see
         * [paddedToBookSize]: refusing one would put the names and keys it holds out of reach, some
         * of them nowhere else any more, and no member could be written from then on.
         */
        fun parse(plaintext: ByteArray): MemberProfileBook? =
            runCatching {
                MemberProfileBook(Json.decodeFromString(JsonObject.serializer(), plaintext.decodeToString()))
            }.getOrNull()
    }
}

/**
 * One member as the vault holds them, see [MemberProfileBook].
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
class VaultMemberProfile(
    val profileEncryptionKey: ByteArray,

    /**
     * See [MemberProfileEntity.isFromOwner][database.data.local.entity.MemberProfileEntity.isFromOwner].
     */
    val isFromOwner: Boolean,

    /** The name read with the key, or null when none was. */
    val fullName: String?,
)

private fun JsonElement.key(): ByteArray? {
    val encoded = ((this as? JsonObject)?.get(PROFILE_ENCRYPTION_KEY) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?: return null

    return runCatching { Base64.Default.decode(encoded) }.getOrNull()
}

private fun JsonElement.isFromOwner(): Boolean =
    ((this as? JsonObject)?.get(FROM_OWNER) as? JsonPrimitive)?.booleanOrNull == true

private fun JsonElement.fullName(): String? =
    ((this as? JsonObject)?.get(FULL_NAME) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
