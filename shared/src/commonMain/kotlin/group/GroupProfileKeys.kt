package group

import contact.ProfileKeyUpdate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** What a [ProfileKeysMessage] names itself, see [ProfileKeysMessage.type]. */
private const val PROFILE_KEYS_TYPE = "profile_keys"

/** The length of every profile encryption key, which `generateSymmetricEncryptionKey` draws. */
private const val PROFILE_KEY_LENGTH = 32

/**
 * What the Welcome to a group hands the members it seats, written by its creator, see
 * [GroupCreator] and [GroupMembershipChanger]: the key to the profile of every member of the group
 * once they are in, theirs included, as the creator's device holds them, see
 * [profileKeysToHandOut]. Not the group's key, which each of them derives from the epoch the
 * Welcome lets them in at, see [GroupKeyChain].
 *
 * JSON, `{"profile_keys":{"<user UUID>":"<Base64>", ...}}`. Nobody but the creator can have written
 * it, the library reading it out of the Welcome he signed, see `WelcomeInspection.welcomePayload`,
 * and it is his word all the same, as the rest of the Welcome is: the keys to the members' profiles
 * only ever fill in for a member nothing is held for, see [MemberProfileRepository.fillGap], his
 * own aside.
 */
@Serializable
private class WelcomePayload(
    /** The key to each member's profile, Base64 encoded, by user UUID. */
    @SerialName("profile_keys")
    val profileKeys: Map<String, String>,
)

/**
 * What the creator of a group sends the members already in it as he adds somebody: the same keys
 * to the members' profiles his Welcome hands the newcomers, see [WelcomePayload], which only those
 * it seats can read. An MLS application message, encrypted in the epoch before the Commit adding
 * them, which the members read whichever of the two the server lists first, the library keeping
 * the secrets of a few epochs past, see [GroupMembershipChanger].
 *
 * JSON, `{"type":"profile_keys","profile_encryption_keys":{"<user UUID>":"<Base64>", ...}}`, told
 * apart from a member's own, see [ProfileKeyUpdate], by its [type].
 */
@Serializable
private class ProfileKeysMessage(
    val type: String,

    /** The key to each member's profile, Base64 encoded, by user UUID. */
    @SerialName("profile_encryption_keys")
    val profileEncryptionKeys: Map<String, String>,
)

/** What the Welcome to a group handed over, see [welcomeHandoverOf]. */
internal class WelcomeHandover(
    /** The key to each member's profile, by user UUID, those that are no profile key left out. */
    val profileKeys: Map<String, ByteArray>,
)

/** The payload of the Welcome to a group, handing over [profileKeys], see [WelcomePayload]. */
internal fun welcomePayloadOf(profileKeys: Map<String, ByteArray>): ByteArray =
    Json.encodeToString(
        WelcomePayload.serializer(),
        WelcomePayload(profileKeys = profileKeys.encoded()),
    ).encodeToByteArray()

/**
 * What [payload], read out of the Welcome to a group, hands over, see [WelcomePayload], or null when
 * it is not one, which hands over nothing.
 */
internal fun welcomeHandoverOf(payload: ByteArray): WelcomeHandover? {
    val read = runCatching {
        Json.decodeFromString(WelcomePayload.serializer(), payload.decodeToString())
    }.getOrNull() ?: return null

    return WelcomeHandover(profileKeys = read.profileKeys.decoded())
}

/** The application message handing [profileKeys] to the members of a group, see [ProfileKeysMessage]. */
internal fun profileKeysMessageOf(profileKeys: Map<String, ByteArray>): ByteArray =
    Json.encodeToString(
        ProfileKeysMessage.serializer(),
        ProfileKeysMessage(type = PROFILE_KEYS_TYPE, profileEncryptionKeys = profileKeys.encoded()),
    ).encodeToByteArray()

/**
 * The keys to the members' profiles [payload] hands over, by user UUID, those that are no profile
 * key left out, or null when it is no [ProfileKeysMessage].
 *
 * Whoever sent it is the caller's to check: any member can send an application message in a
 * group, and only one from its creator hands the keys over.
 */
internal fun profileKeysOf(payload: ByteArray): Map<String, ByteArray>? {
    val message = runCatching {
        Json.decodeFromString(ProfileKeysMessage.serializer(), payload.decodeToString())
    }.getOrNull() ?: return null

    if (message.type != PROFILE_KEYS_TYPE) return null

    return message.profileEncryptionKeys.decoded()
}

/**
 * The key to their own profile a member posts into a group, as [ProfileKeyUpdate] carries it to a
 * contact, see [GroupProfileKeyDelivery], or null when [payload] is no such message, or hands over
 * something that is no profile key.
 *
 * Whose key it is, is whoever MLS says sent it: the caller's to read.
 */
internal fun ownProfileKeyOf(payload: ByteArray): ByteArray? =
    ProfileKeyUpdate.parse(plaintext = payload)?.profileEncryptionKey?.takeIf { it.size == PROFILE_KEY_LENGTH }

/**
 * The key to the profile of each of [memberUuids], the members of a group the user created once a
 * change to them is made, the user [selfUuid] among them, as this device holds them: what the
 * Welcome hands the members it seats, see [WelcomePayload], and an addition the members already
 * in, see [ProfileKeysMessage].
 *
 * The user's own is [ownProfileKey], null when the vault could not be read for it. Every other
 * member was a contact of the user's when they were seated, a creation and an addition seating
 * nobody else, see [inviteeRefusalOf], so the key is the one they handed over as a contact,
 * [contactProfileKey], and for one who is no longer a contact, the one this device learned for
 * them through the groups, [learnedProfileKey], see [MemberProfileRepository.keyFor]. A member
 * this device holds neither for is left out: they post their own into the group all the same, as
 * every member does once seated and each time the creator seats somebody, see
 * [GroupProfileKeyDelivery].
 */
internal suspend fun profileKeysToHandOut(
    memberUuids: Collection<String>,
    selfUuid: String,
    ownProfileKey: ByteArray?,
    contactProfileKey: suspend (userUuid: String) -> ByteArray?,
    learnedProfileKey: suspend (userUuid: String) -> ByteArray?,
): Map<String, ByteArray> = buildMap {
    for (userUuid in memberUuids) {
        val profileKey = if (userUuid == selfUuid) {
            ownProfileKey
        } else {
            contactProfileKey(userUuid) ?: learnedProfileKey(userUuid)
        }

        profileKey?.let { put(userUuid, it) }
    }
}

/** These keys, Base64 encoded. */
private fun Map<String, ByteArray>.encoded(): Map<String, String> = mapValues { (_, key) -> Base64.Default.encode(key) }

/** These keys, decoded, those that are no profile key left out: whatever the creator wrote. */
private fun Map<String, String>.decoded(): Map<String, ByteArray> =
    mapNotNull { (userUuid, key) ->
        runCatching { Base64.Default.decode(key) }.getOrNull()
            ?.takeIf { it.size == PROFILE_KEY_LENGTH }
            ?.let { userUuid to it }
    }.toMap()
