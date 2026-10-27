package group

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** What a [GroupKeyMessage] names itself, see [GroupKeyMessage.type]. */
private const val GROUP_KEY_TYPE = "group_key"

/** The length of every group key, which `generateSymmetricEncryptionKey` draws. */
private const val GROUP_KEY_LENGTH = 32

/**
 * What the creator of a group sends its members once they removed somebody from it: the group's
 * new key, see [GroupMembershipSender], as an MLS application message in the epoch the removal
 * took the group to, which no member it removed is in.
 *
 * JSON, `{"type":"group_key","key":"<Base64>"}`, so that a member can tell it from whatever else
 * comes to be sent in a group, and a later version of it from this one, by its [type].
 */
@Serializable
private class GroupKeyMessage(
    val type: String,

    /** The new key, Base64 encoded. */
    val key: String,
)

/** The application message handing [groupKey] to the members of a group, see [GroupKeyMessage]. */
internal fun groupKeyMessageOf(groupKey: ByteArray): ByteArray =
    Json.encodeToString(
        GroupKeyMessage.serializer(),
        GroupKeyMessage(type = GROUP_KEY_TYPE, key = Base64.Default.encode(groupKey)),
    ).encodeToByteArray()

/**
 * The key [payload] hands over, or null when it is no [GroupKeyMessage], or hands over something
 * that is no group key.
 *
 * Whoever sent it is the caller's to check: any member can send an application message in a
 * group, and only one from its creator hands over its key.
 */
internal fun groupKeyOf(payload: ByteArray): ByteArray? {
    val message = runCatching {
        Json.decodeFromString(GroupKeyMessage.serializer(), payload.decodeToString())
    }.getOrNull() ?: return null

    if (message.type != GROUP_KEY_TYPE) return null

    return runCatching { Base64.Default.decode(message.key) }.getOrNull()?.takeIf { it.size == GROUP_KEY_LENGTH }
}
