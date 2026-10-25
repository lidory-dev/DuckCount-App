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

    /** The one MLS Welcome every invitee joins from, Base64 encoded. */
    @SerialName("welcome_message")
    val welcomeMessage: String,

    /** Everybody the Welcome lets in: every member but the creator, whom the server adds itself. */
    @SerialName("invited_member_uuids")
    val invitedMemberUuids: List<String>,
)

/**
 * Body of the `410` refusing a group creation: every invitee with no account, whether they
 * deleted it or it never existed, sorted.
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
 * [SealedGroupFields.unseal]. [ownerUuid] and [members] are the server's word.
 */
@Serializable
data class GroupDto(
    val uuid: String,

    @SerialName("owner_uuid")
    val ownerUuid: String,

    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val currency: EncryptedFieldDto,

    /** Every member, the caller and those invited who have not joined yet included. */
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

    @SerialName("created_at")
    val createdAt: String,
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
