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
     * The creator's signature over the three as the group's first version, see
     * [FIRST_GROUP_REVISION], sealed under its key, see [GroupVersion.seal], which the server stores
     * the group at. Signed and sealed once, as the group is built, and sent again with the rest of
     * the body as it was.
     */
    val signature: EncryptedFieldDto,

    /**
     * The one MLS Welcome every invitee joins from, Base64 encoded, carrying the group's key and the
     * key to every member's profile, see [welcomePayloadOf].
     */
    @SerialName("welcome_message")
    val welcomeMessage: String,

    /** Everybody the Welcome lets in: every member but the creator, whom the server adds itself. */
    @SerialName("invited_member_uuids")
    val invitedMemberUuids: List<String>,

    /**
     * The creator's copy of the group's key, sealed under their key ring, see
     * [GroupKeyCopies.seal], which the server stores with the group as it takes it: the key
     * outlives a log-out from the moment the group exists. Sealed once, as the group is built, and
     * sent again with the rest of the body as it was.
     */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,
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
 * who remain, the generation it is, see [keyGeneration], the key it replaces sealed under it, see
 * [previousKey], and the group's information sealed under it in place of what a removed member can
 * open, the versions its history holds included, see [previous]. The server keeps the revision it
 * holds, which the rotation leaves as it was, and when it stored each version.
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

    /**
     * The creator's signature over the fields as the revision the server holds, signed again and
     * sealed under the new key, see [GroupVersion.seal], in place of the one sealed under the key
     * it replaces.
     */
    val signature: EncryptedFieldDto,

    /**
     * The creator's copy of the new key, sealed under their key ring, see [GroupKeyCopies.seal],
     * which replaces theirs as the server takes the rotation. The other members' copies stay as
     * they are: each member puts up its own once it reads the new key in the group, see
     * [GroupKeyCopies.putUp].
     */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,

    /**
     * The versions the group's history holds from before the current one, see [GroupHistory],
     * sealed again under the new key, each at its revision, and none for a group never edited: a
     * member added from now on holds that key alone, and reads the history with it. Only those the
     * creator signed that opened under the key this one replaces, see [GroupMembershipSender]: the
     * server drops every one it holds that this does not carry as it takes the rotation, sealed
     * under a key no member added from now on gets.
     */
    val previous: List<PreviousGroupVersionBody>,

    /**
     * Which generation of the group's keys the new key is, see [GroupKeyChain]: one past the one
     * it replaces, which the server takes the group to. Any other it refuses with a `409` naming
     * the group's current generation, after the `409` a resend of a rotation it took hears, see
     * [GroupMembershipAnswer.AlreadyMade]: never heard by this device, which draws the next from
     * the generation it holds, every rotation of the group being its own, and each one it sends
     * kept until the server took it, or refused it writing nothing, see [GroupMembershipSender].
     */
    @SerialName("key_generation")
    val keyGeneration: Long,

    /**
     * The key the new one replaces, sealed under it as the link at [keyGeneration] of the chain
     * back through the group's keys, see [sealKeyLink], which the server stores and lists to the
     * current members, see [GroupApi.keyLinks]: nothing sealed under the old key is sealed again,
     * a group's expenses each staying under the generation they were written in, and a member
     * added from now on, holding the new key alone, opens it to read them.
     */
    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto,
)

/**
 * One version of a group's information from before the current one, sealed again under the new
 * key a rotation hands over, as [RotateGroupKeyBody.previous] carries it: its revision as the
 * server holds it, which names the version the server replaces with it, and its title, description
 * and the creator's signature over them, the currency staying the group's own, sealed as
 * [EditGroupBody]'s are, see [GroupVersion.seal]. Not signed again: what is signed is what the
 * fields open to, which the new key changes nothing of, see [signedAs].
 */
@Serializable
data class PreviousGroupVersionBody(
    val revision: Long,

    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val signature: EncryptedFieldDto,
)

/**
 * Body of `PUT /groups/{group_uuid}`, which the creator alone sends to edit the group's information,
 * see [GroupEditor]: its title and description, the currency staying the one it was created in.
 *
 * Nothing names the edit but its [revision]: the server takes it only when it comes after the
 * revision it holds, and answers `409` otherwise, so the same edit sent again is never taken twice,
 * nor one made over a version this device never saw.
 */
@Serializable
data class EditGroupBody(
    /** One more than the revision of the version edited, see [GroupVersion]. */
    val revision: Long,

    /** Sealed under the group's key as [CreateGroupBody]'s are, see [GroupFields.seal]. */
    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    /**
     * The creator's signature over the new title and description, with the group's currency, as
     * [revision], sealed under the group's key, see [GroupVersion.seal].
     */
    val signature: EncryptedFieldDto,
)

/**
 * Body of `PUT /groups/{group_uuid}/key-copy`, which any current member sends of their own copy of
 * the group's key, see [GroupApi.saveKeyCopy].
 */
@Serializable
data class SaveGroupKeyCopyBody(
    /** Sealed under the caller's key ring, see [GroupKeyCopies.seal]: opaque to the server. */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,

    /**
     * Which generation of the group's keys the copy holds, see [GroupKeyChain], which the server
     * lists it with, see [GroupDto.keyCopyGeneration]: one it refuses with a `400` past the
     * group's current one, which no member holds before the server took the rotation to it.
     */
    @SerialName("key_generation")
    val keyGeneration: Long,
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
 * [SealedGroupFields.unseal], with the creator's signature over them as [revision], see
 * [GroupVersion], and the caller's copy of that key under their key ring, see [keyCopy].
 * [ownerUuid], [creatorUuid] and [members] are the server's word, see [GroupSync]. Of a
 * group whose MLS state this device holds, they decide one thing alone: who is no longer a member,
 * whom its creator's device removes. A group brought back after a log-out, with no tree to tell
 * them, takes its creator and its members as they are listed. And a group this device declined is not left while the
 * server lists the user as its owner.
 */
@Serializable
data class GroupDto(
    val uuid: String,

    @SerialName("owner_uuid")
    val ownerUuid: String,

    /**
     * Who created the group, and so signed every version of its information, see [GroupVersion]:
     * [ownerUuid] but for a group a deletion of its creator's account handed over to another
     * member, which the server lists under them, the versions staying the creator's. Whom a group
     * brought back after a log-out is held to, there being no Welcome left to name them, see
     * [GroupSync].
     */
    @SerialName("creator_uuid")
    val creatorUuid: String,

    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val currency: EncryptedFieldDto,

    /**
     * Which version of the group's information the three are, see [GroupVersion]: stored by the
     * server as it takes its owner's edit coming after the one it holds, and covered by the
     * creator's [signature], so the server's word for it is only as good as that signature.
     */
    val revision: Long,

    /** The creator's signature over the three as [revision], sealed under the group's key, see [GroupVersion.seal]. */
    val signature: EncryptedFieldDto,

    /**
     * Every current member, the caller and those invited who have not joined yet included: not
     * those who left the group, see [GroupApi.leave], nor accounts deleted since, both of whom stay
     * in its tree until its creator's device removes them, see [GroupSync], nor those it removed.
     */
    val members: List<GroupMemberDto>,

    /**
     * The caller's own copy of the group's key, see [GroupKeyCopies], or null when the server holds
     * none for them: one that never went up, or that it dropped as its creator removed them, or as
     * they left. Nobody else's is ever listed to them.
     */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto?,

    /**
     * Which generation of the group's keys [keyCopy] holds, as the caller said putting it up, see
     * [SaveGroupKeyCopyBody.keyGeneration], null exactly when [keyCopy] is: the server's word,
     * which the copy is bound to, see [GroupKeyCopies.open], so that a copy listed as another
     * generation than its own opens as none.
     */
    @SerialName("key_copy_generation")
    val keyCopyGeneration: Long?,

    /**
     * Which generation of the group's keys is its current one, see [GroupKeyChain], which the
     * server takes whatever is written in the group under: one more with each rotation its
     * creator made, see [RotateGroupKeyBody.keyGeneration]. The server's word: a device holding an
     * older one waits for the creator's message handing the newer one over, see [GroupSync].
     */
    @SerialName("key_generation")
    val keyGeneration: Long,
)

/**
 * Mirrors the server's `GroupKeyLink`: one link of the chain back through a group's keys, as `GET
 * /groups/{group_uuid}/key-links` lists them, see [GroupKeyChain]: the key of the generation
 * before [generation], sealed under the key of [generation] by the creator's device as it rotated
 * the group's key to it, see [RotateGroupKeyBody.previousKey].
 */
@Serializable
data class GroupKeyLinkDto(
    val generation: Long,

    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto,
)

/** One member of a [GroupDto]. */
@Serializable
data class GroupMemberDto(
    @SerialName("user_uuid")
    val userUuid: String,
)

/**
 * Body of `GET /groups/{group_uuid}/history`: the versions of a group's information the server
 * keeps, [current], the one `GET /groups` lists, and [previous], those its edits replaced, newest
 * first, the last nine of them, see [GroupHistory].
 */
@Serializable
data class GroupHistoryResponse(
    val current: GroupHistoryVersionDto,

    val previous: List<GroupHistoryVersionDto>,
)

/**
 * One version of a group's information as its history holds it, see [GroupHistoryResponse]: its
 * title, description and the creator's signature over them as [revision], sealed under the group's
 * key as [GroupDto]'s are, and when the server stored it. No currency: no edit changes it, so the
 * group's own is what every version is checked with, see [GroupHistoryVersionDto.unseal].
 */
@Serializable
data class GroupHistoryVersionDto(
    val revision: Long,

    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val signature: EncryptedFieldDto,

    /**
     * When the server stored this version, as the group was created or the edit that made it was
     * taken, RFC 3339 in UTC to the millisecond, as the API writes it: the server's word, which
     * nothing signs. A key rotation leaves it as it was.
     */
    @SerialName("revised_at")
    val revisedAt: String,
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

/**
 * Body of `GET /groups/{group_uuid}/messages`: a page of the caller's pending messages there,
 * oldest first, and [nextCursor] to ask for the next page with, null when this is the last, see
 * [GroupApi.getGroupMessagesPage]. Every page read, see [GroupApi.getGroupMessages], it holds them
 * all, with no next cursor.
 */
@Serializable
data class ListGroupMessagesResponse(
    val messages: List<GroupMessageDto>,

    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

/** Body of `POST /groups/{group_uuid}/messages/acknowledgements`. */
@Serializable
data class AcknowledgeGroupMessagesBody(
    @SerialName("message_uuids")
    val messageUuids: List<String>,
)
