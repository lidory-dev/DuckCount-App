package group

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** What a [GroupKeyMessage] names itself, see [GroupKeyMessage.type]. */
private const val GROUP_KEY_TYPE = "group_key"

/** The length of every group key, which `generateSymmetricEncryptionKey` draws. */
internal const val GROUP_KEY_LENGTH = 32

/**
 * What the creator of a group sends its members once they removed somebody from it: the group's
 * new key, see [GroupMembershipSender], as an MLS application message in the epoch the removal
 * took the group to, which no member it removed is in, with the generation it is of the group's
 * keys, see [GroupKeyChain].
 *
 * JSON, `{"type":"group_key","key":"<Base64>","key_generation":<n>}`, so that a member can tell it
 * from whatever else comes to be sent in a group, and a later version of it from this one, by its
 * [type].
 */
@Serializable
private class GroupKeyMessage(
    val type: String,

    /** The new key, Base64 encoded. */
    val key: String,

    /** Which generation of the group's keys [key] is, one past the key it replaces. */
    @SerialName("key_generation")
    val keyGeneration: Long,
)

/**
 * The application message handing [groupKey], the generation [keyGeneration] of the group's keys,
 * to the members of a group, see [GroupKeyMessage].
 */
internal fun groupKeyMessageOf(groupKey: ByteArray, keyGeneration: Long): ByteArray =
    Json.encodeToString(
        GroupKeyMessage.serializer(),
        GroupKeyMessage(type = GROUP_KEY_TYPE, key = Base64.Default.encode(groupKey), keyGeneration = keyGeneration),
    ).encodeToByteArray()

/**
 * The key [payload] hands over, with its generation, or null when it is no [GroupKeyMessage], or
 * hands over something that is no group key, or no generation of one.
 *
 * Whoever sent it is the caller's to check: any member can send an application message in a
 * group, and only one from its creator hands over its key.
 */
internal fun groupKeyOf(payload: ByteArray): GroupKeyGeneration? {
    val message = runCatching {
        Json.decodeFromString(GroupKeyMessage.serializer(), payload.decodeToString())
    }.getOrNull() ?: return null

    if (message.type != GROUP_KEY_TYPE || message.keyGeneration < FIRST_KEY_GENERATION) return null

    val key = runCatching { Base64.Default.decode(message.key) }.getOrNull()?.takeIf { it.size == GROUP_KEY_LENGTH }
        ?: return null

    return GroupKeyGeneration(generation = message.keyGeneration, key = key)
}
