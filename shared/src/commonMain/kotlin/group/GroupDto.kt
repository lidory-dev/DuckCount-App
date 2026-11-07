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

    /**
     * The three fields sealed under the group's key of the epoch its creation leaves it at, the
     * first generation, see [FIRST_KEY_GENERATION], each bound to itself and the group, see
     * [GroupFields.seal].
     */
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
     * The one MLS Welcome every invitee joins from, Base64 encoded, carrying the key to every
     * member's profile, see [welcomePayloadOf]: the group's key each of them derives from the
     * epoch the Welcome lets them in at, see [GroupKeyChain].
     */
    @SerialName("welcome_message")
    val welcomeMessage: String,

    /** Everybody the Welcome lets in: every member but the creator, whom the server adds itself. */
    @SerialName("invited_member_uuids")
    val invitedMemberUuids: List<String>,

    /**
     * The creator's copy of the group's key, sealed under their key ring, see
     * [GroupKeyCopies.sealer], which the server stores with the group as it takes it: the key
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
 * no account, in a [DeletedInviteesResponse]. A Commit for an epoch the group has left is answered
 * the JSON `409` naming the one it is at, see [GroupMembershipAnswer.KeyGenerationNotCurrent].
 */
@Serializable
data class AddGroupMembersBody(
    /** 16 random bytes, Base64 encoded, drawn for this change alone. */
    val nonce: String,

    /**
     * The MLS Commit adding them, Base64 encoded, for every member already in the group, built in
     * the epoch the group is at, which the server reads from its header and takes one further.
     */
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
     * The one MLS Welcome every new member joins from, Base64 encoded, carrying the key to every
     * member's profile, see [welcomePayloadOf].
     */
    @SerialName("welcome_message")
    val welcomeMessage: String,

    @SerialName("new_member_uuids")
    val newMemberUuids: List<String>,

    /**
     * The key of the epoch the Commit is built in sealed under the key of the epoch it leads to, as
     * the link at that generation of the chain back through the group's keys, see [sealKeyLink],
     * which the server stores with the Commit: a member added from then on, who derives the newer
     * key alone, opens it to read what was sealed before. Every Commit carries its own, see
     * [GroupKeyChain].
     */
    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto,

    /**
     * The committer's copy of the key of the epoch the Commit leads to, sealed under their key ring,
     * see [GroupKeyCopies], which the server stores with the Commit in place of the one they had.
     */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,
)

/**
 * Body of `DELETE /groups/{group_uuid}/members`, which the creator alone may send, named by its
 * [nonce] as an addition is, and held to the group's epoch as an addition is, see
 * [AddGroupMembersBody].
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

    /** The link back to the key of the epoch the Commit is built in, see [AddGroupMembersBody.previousKey]. */
    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto,

    /** The committer's copy of the key the Commit leads to, see [AddGroupMembersBody.keyCopy]. */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,
)

/**
 * Body of `DELETE /groups/{group_uuid}/departed`, which any current member may send: the removal
 * of members who left the group, or whose account was deleted, and whom no Commit has removed yet,
 * see [GroupDto.departed]. Named by its [nonce] and held to the group's epoch as an addition is,
 * see [AddGroupMembersBody]. The server refuses with a `400` a target it does not list as departed.
 *
 * The one Commit the members' devices take from a member who is not the group's creator, and only
 * when it removes exactly the members the server stored it as removing, see
 * [GroupMessageDto.departedMemberUuids], and does nothing else.
 */
@Serializable
data class RemoveDepartedMembersBody(
    /** 16 random bytes, Base64 encoded, drawn for this change alone. */
    val nonce: String,

    /** The MLS Commit removing them, Base64 encoded, and nothing else. */
    @SerialName("commit_message")
    val commitMessage: String,

    @SerialName("removed_member_uuids")
    val removedMemberUuids: List<String>,

    /** The link back to the key of the epoch the Commit is built in, see [AddGroupMembersBody.previousKey]. */
    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto,

    /** The committer's copy of the key the Commit leads to, see [AddGroupMembersBody.keyCopy]. */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,
)

/**
 * Body of `POST /groups/{group_uuid}/commits/replacement`, which the owner alone may send, and which
 * only the creator's device sends: a Commit for the epoch its device is at, [replacesEpoch], in
 * place of one every member's device refused there for good, and of every one stored after it, see
 * [GroupSync]. Named by its [nonce] as an addition is, see [AddGroupMembersBody]. The server
 * answers the plain `409` "Nothing to replace." when no other member's Commit is recorded for that
 * epoch.
 */
@Serializable
data class ReplaceGroupCommitBody(
    /** 16 random bytes, Base64 encoded, drawn for this change alone. */
    val nonce: String,

    /** The epoch the creator's device is at, the one the Commit is built in. */
    @SerialName("replaces_epoch")
    val replacesEpoch: Long,

    /**
     * The MLS Commit, Base64 encoded, removing [removedMemberUuids]: those who sent the Commits it
     * replaces and whoever departed, or nobody.
     */
    @SerialName("commit_message")
    val commitMessage: String,

    @SerialName("removed_member_uuids")
    val removedMemberUuids: List<String>,

    /** The link back to the key of the epoch the Commit is built in, see [AddGroupMembersBody.previousKey]. */
    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto,

    /** The committer's copy of the key the Commit leads to, see [AddGroupMembersBody.keyCopy]. */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,
)

/**
 * Body of `POST /groups/{group_uuid}/commits/rejoin`, which any current member may send: the Commit
 * from outside the group with which their device rejoins it once a log-out took its MLS state, see
 * [GroupRejoiner], built from a GroupInfo another member published of the epoch the server lists
 * the group in, see [GroupInfoDto]. It removes the leaf the user had and seats the one this device
 * holds, under the same signature key, and changes nobody: every member's device takes it from any
 * member, and nothing else from outside the group. Named by its [nonce] and held to the group's
 * epoch as an addition is, see [AddGroupMembersBody]. The server stores it as that epoch's one
 * Commit, drops whatever still waited for the caller in the group, every bit of it from epochs
 * this device holds nothing of, and closes their request, see [GroupApi.requestRejoin].
 */
@Serializable
data class RejoinGroupBody(
    /** 16 random bytes, Base64 encoded, drawn for this rejoin alone. */
    val nonce: String,

    /**
     * The MLS Commit, Base64 encoded, a PublicMessage, as MLS has every Commit from outside a group
     * travel: the one thing of the group the server reads in the clear, but for the GroupInfo it
     * was built from.
     */
    @SerialName("commit_message")
    val commitMessage: String,

    /** The rejoiner's copy of the key the Commit leads to, see [AddGroupMembersBody.keyCopy]. */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,

    /**
     * The link back to the key of the epoch the Commit is built in, see
     * [AddGroupMembersBody.previousKey], when this device holds that key from the user's copy, see
     * [GroupKeyCopies], and null, which leaves it out, when it does not: the Commit gives a device
     * from outside the group the key of the epoch it leads to alone, so that only a copy of the
     * epoch it is built in can make it. The members' devices publish their own then, as they reach
     * that epoch, see [GroupKeyLinks].
     */
    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto? = null,
)

/**
 * Body of `PUT /groups/{group_uuid}`, which the creator alone sends to edit the group's information,
 * see [GroupEditor]: its title and description, the currency staying the one it was created in.
 *
 * Nothing names the edit but its [revision]: the server takes it only when it comes after the
 * revision it holds, and answers `409` otherwise, so the same edit sent again is never taken twice,
 * nor one made over a version this device never saw. Before that, a version sealed under a key
 * that is not the group's current one is answered the JSON `409` naming the current generation,
 * see [GroupEditAnswer.KeyGenerationNotCurrent].
 */
@Serializable
data class EditGroupBody(
    /** One more than the revision of the version edited, see [GroupVersion]. */
    val revision: Long,

    /**
     * Which generation of the group's keys the title, description and signature are sealed under,
     * see [GroupKeyChain]: the group's current one, which the server stores the version with.
     */
    @SerialName("key_generation")
    val keyGeneration: Long,

    /** Sealed under the group's key of [keyGeneration], see [GroupFields.seal]. */
    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    /**
     * The creator's signature over the new title and description, with the group's currency, as
     * [revision], sealed under the group's key of [keyGeneration], see [GroupVersion.seal].
     */
    val signature: EncryptedFieldDto,
)

/**
 * Body of `PUT /groups/{group_uuid}/key-copy`, which any current member sends of their own copy of
 * the group's key, see [GroupApi.saveKeyCopy].
 */
@Serializable
data class SaveGroupKeyCopyBody(
    /** Sealed under the caller's key ring, see [KeyCopySealer.seal]: opaque to the server. */
    @SerialName("key_copy")
    val keyCopy: EncryptedFieldDto,

    /**
     * Which generation of the group's keys the copy holds, see [GroupKeyChain], which the server
     * lists it with, see [GroupDto.keyCopyGeneration]: one it refuses with a `400` past the
     * group's current one, which no member reaches before the server stored the Commit leading
     * there.
     */
    @SerialName("key_generation")
    val keyGeneration: Long,
)

/**
 * Body of `PUT /groups/{group_uuid}/key-links/{generation}`, which any current member sends, see
 * [GroupApi.publishKeyLink]: their own link at that generation of the chain back through the
 * group's keys, once none the server lists there leads back to the key they hold before it, see
 * [GroupSync].
 */
@Serializable
data class PublishGroupKeyLinkBody(
    /** The key of the generation before, sealed under the key of the generation, see [sealKeyLink]. */
    @SerialName("previous_key")
    val previousKey: EncryptedFieldDto,
)

/**
 * Body of `PUT /groups/{group_uuid}/group-info`, which any current member sends once the server
 * lists the group with [GroupDto.groupInfoWanted]: the GroupInfo of the epoch their device is in,
 * for a member a log-out took the group's MLS state from to rejoin it from, see [GroupRejoiner].
 * The server takes one of the epoch the group is in alone, answering the JSON `409` of
 * [KeyGenerationConflictResponse] otherwise, and only while another member asks for one, answering
 * the plain `409` "No rejoin requested." otherwise.
 */
@Serializable
data class PublishGroupInfoBody(
    /**
     * The GroupInfo, an MLS message, Base64 encoded, see [mls.rejoinGroupInfoIn]: the group's tree,
     * with its members' user UUIDs and public keys, which the server reads, and nothing anybody could
     * read the group with.
     */
    @SerialName("group_info")
    val groupInfo: String,
)

/**
 * One member who published a GroupInfo of the epoch a group is in, as `GET
 * /groups/{group_uuid}/group-infos` lists them, oldest first, see [GroupRejoiner].
 */
@Serializable
data class GroupInfoPublisherDto(
    @SerialName("publisher_uuid")
    val publisherUuid: String,

    /** The epoch it is of: the group's current one, the server dropping every other as the group moves on. */
    val epoch: Long,
)

/**
 * Body of `GET /groups/{group_uuid}/group-infos/{publisher_uuid}`: the GroupInfo [publisherUuid]
 * published of [epoch], see [PublishGroupInfoBody]. The server's word for who published it, which
 * the library does not take: it rejoins only from a GroupInfo signed by a member this device pinned,
 * or by the user, see [mls.rejoinGroupOf].
 */
@Serializable
data class GroupInfoDto(
    @SerialName("publisher_uuid")
    val publisherUuid: String,

    val epoch: Long,

    /** The GroupInfo, Base64 encoded, as it was published. */
    @SerialName("group_info")
    val groupInfo: String,
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
 * The three fields are sealed under the group's keys, which the server never holds, see
 * [SealedGroupVersion.unseal]: the title and description, with the creator's signature over them
 * as [revision], see [GroupVersion], under the generation [informationKeyGeneration], and the
 * currency under the first, as the group was created. The caller's copy of its key is sealed under
 * their key ring, see [keyCopy]. [ownerUuid], [members] and [departed] are the server's word, see
 * [GroupSync]. Of a group whose MLS state this device holds, they decide one thing alone: whom this
 * device removes, of those still in its tree. A group brought back after a log-out, with no tree to
 * tell them, takes its creator and its members as they are listed, until it is rejoined, see
 * [GroupRejoiner], from a tree holding exactly those members and those departed.
 */
@Serializable
data class GroupDto(
    val uuid: String,

    /**
     * Who created the group, and so signed every version of its information, see [GroupVersion]. Whom
     * a group brought back after a log-out is held to, there being no Welcome left to name them, see
     * [GroupSync].
     */
    @SerialName("owner_uuid")
    val ownerUuid: String,

    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val currency: EncryptedFieldDto,

    /**
     * Which version of the group's information the three are, see [GroupVersion]: stored by the
     * server as it takes its owner's edit coming after the one it holds, and covered by the
     * creator's [signature], so the server's word for it is only as good as that signature.
     */
    val revision: Long,

    /**
     * The creator's signature over the three as [revision], sealed under the group's key of
     * [informationKeyGeneration], see [GroupVersion.seal].
     */
    val signature: EncryptedFieldDto,

    /**
     * Which generation of the group's keys the title, description and signature are sealed under:
     * the epoch its creator edited them in, or the first for the information it was created with.
     * Nothing is sealed again as the group moves on, so a member holding a later key gets this one
     * back through the chain, see [GroupKeyChain]. The currency is under the first, always.
     */
    @SerialName("information_key_generation")
    val informationKeyGeneration: Long,

    /**
     * Every current member, the caller and those invited who have not joined yet included: not
     * those who left the group, see [GroupApi.leave], nor accounts deleted since, both of whom stay
     * in its tree until a member's device removes them, see [departed], nor those a Commit removed.
     */
    val members: List<GroupMemberDto>,

    /**
     * The members who left the group, or whose account was deleted, and whom no Commit the server
     * stored has removed from its MLS tree yet, oldest departure first: whom the creator's device
     * removes at once, and any other member's once they left [DEPARTED_GRACE] ago, see
     * [GroupSync]. Never what decides whether a Commit is taken, see
     * [GroupMessageDto.departedMemberUuids].
     */
    val departed: List<DepartedMemberDto>,

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
     * Which generation of the group's keys is its current one, see [GroupKeyChain]: the group's MLS
     * epoch, which the server takes whatever is written in the group under, one more with every
     * Commit it stores. The server's word: a device at an older epoch has Commits still to read,
     * see [GroupSync], or refused one every member's device refused, and waits for the creator's
     * device to replace it.
     */
    @SerialName("key_generation")
    val keyGeneration: Long,

    /**
     * Whether another current member asked for a GroupInfo to rejoin the group from, see
     * [GroupApi.requestRejoin], and the server holds none from the caller of the epoch the group is
     * in: their device then publishes its own, if it is in that epoch, see [GroupRejoiner]. False
     * from a server that predates rejoins, which lists no such field.
     */
    @SerialName("group_info_wanted")
    val groupInfoWanted: Boolean = false,
)

/** One member listed among the [GroupDto.departed]. */
@Serializable
data class DepartedMemberDto(
    @SerialName("user_uuid")
    val userUuid: String,

    /**
     * When they left, or their account was deleted, RFC 3339 in UTC to the millisecond, as the API
     * writes it: the server's word, which only decides when a member who is not the creator removes
     * them, see [DEPARTED_GRACE].
     */
    @SerialName("left_at")
    val leftAt: String,
)

/**
 * Mirrors the server's `GroupKeyLink`: one candidate for a link of the chain back through a
 * group's keys, as `GET /groups/{group_uuid}/key-links` lists them, see [GroupKeyChain]: the key of
 * the generation before [generation], sealed under the key of [generation] by [publisherUuid],
 * whose Commit led there, see [AddGroupMembersBody.previousKey], or who reached it and found no
 * link leading back to the key they held, see [PublishGroupKeyLinkBody]. One per member and
 * generation at most, and any member could publish one that leads nowhere: a candidate counts only
 * once what it opens to opens something sealed under its own generation.
 */
@Serializable
data class GroupKeyLinkDto(
    val generation: Long,

    @SerialName("publisher_uuid")
    val publisherUuid: String,

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
 * key of [keyGeneration] as [GroupDto]'s are, and when the server stored it. No currency: no edit
 * changes it, so the group's own is what every version is checked with, see
 * [GroupHistoryVersionDto.unseal].
 */
@Serializable
data class GroupHistoryVersionDto(
    val revision: Long,

    /** Which generation of the group's keys the version is sealed under, see [GroupKeyChain]. */
    @SerialName("key_generation")
    val keyGeneration: Long,

    val title: EncryptedFieldDto,

    val description: EncryptedFieldDto,

    val signature: EncryptedFieldDto,

    /**
     * When the server stored this version, as the group was created or the edit that made it was
     * taken, RFC 3339 in UTC to the millisecond, as the API writes it: the server's word, which
     * nothing signs.
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

    /**
     * For a Commit stored through `DELETE /groups/{group_uuid}/departed`, the members the server
     * checked had departed as it stored it, see [RemoveDepartedMembersBody]; none for every other
     * message. The one list a Commit from a member who is not the group's creator is held to, see
     * [mls.processGroupMessageIn], so that every member's device decides alike about every Commit,
     * whatever each of them makes of who departed.
     */
    @SerialName("departed_member_uuids")
    val departedMemberUuids: List<String>,
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
