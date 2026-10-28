package group

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /groups`.
 *
 * Every field but the nonce and the invitees is opaque to the server. The group's UUID is not
 * sent: the server derives it from [nonce] and the creator's UUID, as the creator's device did
 * before saving the group under it, see [GroupCreator]. The device sends this same body again
 * until it hears back, see [database.data.local.entity.PendingGroupCreationEntity]: a nonce the
 * creator sent before answers `409`, so a send whose answer was lost never creates the group
 * twice.
 */
@Serializable
data class CreateGroupBody(
    /** 16 random bytes, Base64 encoded, see `group_uuid` in lib-duckcount. */
    val nonce: String,

    /** The three fields sealed under the group's key, each bound to itself and the group, see [GroupFields.seal]. */
    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val currency: EncryptedFieldDto,

    /**
     * The one MLS Welcome every invitee joins from, Base64 encoded, carrying the group's key and the
     * key to every member's profile, see [welcomePayloadOf].
     */
    @SerialName("welcome_message")
    val welcomeMessage: String,

    /** Everybody the Welcome lets in: every member but the creator, whom the server adds itself. */
    @SerialName("invited_member_uuids")
    val invitedMemberUuids: List<String>,
)

/**
 * Body of `POST /groups/{group_uuid}/members`, which the creator alone may send.
 *
 * The change is named by [nonce] and the creator's UUID, as a creation is, and recorded with the
 * group, so that the same body sent again answers `409`, see
 * [database.data.local.entity.PendingMembershipChangeEntity]. A `410` names every new member with
 * no account, in a [DeletedInviteesResponse].
 */
@Serializable
data class AddGroupMembersBody(
    /** 16 random bytes, Base64 encoded, drawn for this change alone. */
    val nonce: String,

    /** The MLS Commit adding them, Base64 encoded, for every member already in the group. */
    @SerialName("commit_message")
    val commitMessage: String,

    /**
     * The MLS application message handing the members already in the group the key to the profile
     * of every member once the new ones are in, Base64 encoded, see [profileKeysMessageOf]: what the
     * Welcome hands the new ones, which only they can read. Encrypted in the epoch before the
     * Commit, which the server delivers it ahead of, to the Commit's recipients alone.
     */
    @SerialName("profile_keys_message")
    val profileKeysMessage: String,

    /**
     * The one MLS Welcome every new member joins from, Base64 encoded, carrying the group's key and
     * the key to every member's profile, see [welcomePayloadOf].
     */
    @SerialName("welcome_message")
    val welcomeMessage: String,

    @SerialName("new_member_uuids")
    val newMemberUuids: List<String>,
)

/**
 * Body of `DELETE /groups/{group_uuid}/members`, which the creator alone may send, named by its
 * [nonce] as an addition is, see [AddGroupMembersBody].
 */
@Serializable
data class RemoveGroupMembersBody(
    /** 16 random bytes, Base64 encoded, drawn for this change alone. */
    val nonce: String,

    /**
     * The MLS Commit removing them, Base64 encoded, for every member, those it removes included,
     * which is how their devices find out.
     */
    @SerialName("commit_message")
    val commitMessage: String,

    @SerialName("removed_member_uuids")
    val removedMemberUuids: List<String>,
)

/**
 * Body of `PUT /groups/{group_uuid}/key`, which the creator sends once a removal is applied,
 * named by its [nonce] as an addition is, see [AddGroupMembersBody]: the new key for the members
 * who remain, and the group's fields sealed under it in place of those a removed member can open.
 */
@Serializable
data class RotateGroupKeyBody(
    /** 16 random bytes, Base64 encoded, drawn for this change alone. */
    val nonce: String,

    /**
     * The MLS application message handing the new key over, Base64 encoded, see [groupKeyMessageOf],
     * sent in the epoch the removal took the group to, which the members it removed are not in.
     */
    @SerialName("key_message")
    val keyMessage: String,

    /** The group's fields as they are, sealed under the new key, see [GroupFields.seal]. */
    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val currency: EncryptedFieldDto,
)

/**
 * Body of the `410` refusing a group creation, or an addition to a group: every invitee with no
 * account, whether they deleted it or it never existed, sorted.
 */
@Serializable
data class DeletedInviteesResponse(
    @SerialName("deleted_user_uuids")
    val deletedUserUuids: List<String>,
)

/**
 * Mirrors the server's `Group`: one group the caller is a member of, as `GET /groups` lists it.
 *
 * The three fields are sealed under the group's key, which the server never holds, see
 * [SealedGroupFields.unseal]. [ownerUuid] and [members] are the server's word, see [GroupSync]. Of a
 * group whose MLS state this device holds, they decide one thing alone: who is no longer a member,
 * whom its creator's device removes. A group brought back after a log-out, with no tree to tell
 * them, takes both as they are listed. And a group this device declined is not left while the
 * server lists the user as its owner.
 */
@Serializable
data class GroupDto(
    val uuid: String,

    @SerialName("owner_uuid")
    val ownerUuid: String,

    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val currency: EncryptedFieldDto,

    /**
     * Every current member, the caller and those invited who have not joined yet included: not
     * those who left the group, see [GroupApi.leave], nor accounts deleted since, both of whom stay
     * in its tree until its creator's device removes them, see [GroupSync], nor those it removed.
     */
    val members: List<GroupMemberDto>,
)

/** One member of a [GroupDto]. */
@Serializable
data class GroupMemberDto(
    @SerialName("user_uuid")
    val userUuid: String,
)

/**
 * Mirrors the server's `GroupMessage`: one message waiting for the caller in a group, a Welcome
 * among them. [senderUserUuid] is the server's word for who sent it, MLS authenticates the actual
 * sender once the message is read.
 */
@Serializable
data class GroupMessageDto(
    val uuid: String,

    @SerialName("sender_user_uuid")
    val senderUserUuid: String,

    /** The MLS message, Base64 encoded, opaque to the server. */
    val ciphertext: String,

    /**
     * When the server stored it, RFC 3339 in UTC to the millisecond, as the API writes it: what it
     * lists the messages in the order of, and what ranks the keys members post of their own, see
     * [GroupSync.takeProfileKeys].
     */
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * Body of `POST /groups/{group_uuid}/messages`, which any current member may send: an MLS
 * application message, which the server hands every other current member, see
 * [GroupApi.postMessage].
 */
@Serializable
data class PostGroupMessageBody(
    /** The MLS message, Base64 encoded, opaque to the server. */
    val ciphertext: String,
)

/** Body of the `200` to `POST /groups/{group_uuid}/messages`: the message the server minted for the post. */
@Serializable
data class PostGroupMessageResponse(
    val uuid: String,
)

/** Body of `GET /groups/{group_uuid}/messages`: the caller's pending messages there, oldest first. */
@Serializable
data class ListGroupMessagesResponse(
    val messages: List<GroupMessageDto>,
)

/** Body of `POST /groups/{group_uuid}/messages/acknowledgements`. */
@Serializable
data class AcknowledgeGroupMessagesBody(
    @SerialName("message_uuids")
    val messageUuids: List<String>,
)
