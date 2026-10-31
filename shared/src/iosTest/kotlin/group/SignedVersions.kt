package group

import database.data.local.entity.GroupEntity
import database.data.remote.EncryptedFieldDto
import uniffi.lib_duckcount.MlsClient
import kotlin.io.encoding.Base64
import kotlin.test.assertNotNull

/**
 * These fields as [revision] of the group [groupUuid], signed by [creator] as their device signs
 * every version of a group of theirs, see [signedAs], and sealed under [groupKey] for that group,
 * see [GroupVersion.seal], the currency under [currencyKey], the key of the first generation: what
 * the server holds of it once [creator] created it, or edited it in a later epoch than the first.
 */
internal fun GroupFields.sealedAs(
    creator: MlsClient,
    groupKey: ByteArray,
    groupUuid: String,
    revision: Long = FIRST_GROUP_REVISION,
    currencyKey: ByteArray = groupKey,
): SealedGroupVersion {
    val version = assertNotNull(signedAs(client = creator, groupUuid = groupUuid, revision = revision))
    val sealed = assertNotNull(version.seal(groupKey = groupKey, groupUuid = groupUuid))
    val currency = assertNotNull(seal(groupKey = currencyKey, groupUuid = groupUuid)).currency

    return SealedGroupVersion(
        fields = SealedGroupFields(title = sealed.fields.title, description = sealed.fields.description, currency = currency),
        revision = sealed.revision,
        signature = sealed.signature,
    )
}

/**
 * The group [uuid] as the server lists it, owned by [ownerUuid] and created by [creatorUuid], with
 * [memberUuids], [departed] since, [version] of its information, sealed under the generation
 * [informationKeyGeneration], the caller's copy of its key, [keyCopy], if any, of the generation
 * [keyCopyGeneration], and the generation [keyGeneration] its key is at.
 */
internal fun listedGroupOf(
    uuid: String,
    ownerUuid: String,
    version: SealedGroupVersion,
    memberUuids: List<String>,
    keyCopy: EncryptedFieldDto? = null,
    creatorUuid: String = ownerUuid,
    keyGeneration: Long = FIRST_KEY_GENERATION,
    keyCopyGeneration: Long? = keyCopy?.let { keyGeneration },
    informationKeyGeneration: Long = FIRST_KEY_GENERATION,
    departed: List<DepartedMemberDto> = emptyList(),
) = GroupDto(
    uuid = uuid,
    ownerUuid = ownerUuid,
    creatorUuid = creatorUuid,
    title = version.fields.title,
    description = version.fields.description,
    currency = version.fields.currency,
    revision = version.revision,
    signature = version.signature,
    informationKeyGeneration = informationKeyGeneration,
    members = memberUuids.map { GroupMemberDto(userUuid = it) },
    departed = departed,
    keyCopy = keyCopy,
    keyCopyGeneration = keyCopyGeneration,
    keyGeneration = keyGeneration,
)

/**
 * The version an edit, [body], puts on the server in place of this one: its title, description,
 * revision and signature, the currency staying as it was, see [GroupEditor].
 */
internal fun SealedGroupVersion.editedBy(body: EditGroupBody) = SealedGroupVersion(
    fields = SealedGroupFields(title = body.title, description = body.description, currency = fields.currency),
    revision = body.revision,
    signature = body.signature,
)

/**
 * This version as the group's history holds it, see [GroupHistoryVersionDto]: its fields but the
 * currency, which the history does not hold, sealed under the generation [keyGeneration], stored by
 * the server at [revisedAt].
 */
internal fun SealedGroupVersion.inHistory(
    revisedAt: String = "2026-10-29T08:15:42.317Z",
    keyGeneration: Long = FIRST_KEY_GENERATION,
) = GroupHistoryVersionDto(
    revision = revision,
    keyGeneration = keyGeneration,
    title = fields.title,
    description = fields.description,
    signature = signature,
    revisedAt = revisedAt,
)

/**
 * The message [uuid] as the server lists it in a group, [message] from [senderUuid], stored at
 * [createdAt], and [departedMemberUuids], those it was stored as removing for having departed, see
 * [GroupMessageDto.departedMemberUuids].
 */
internal fun groupMessageOf(
    uuid: String,
    senderUuid: String,
    message: ByteArray,
    createdAt: String = "2026-10-29T08:15:42.317Z",
    departedMemberUuids: List<String> = emptyList(),
) = GroupMessageDto(
    uuid = uuid,
    senderUserUuid = senderUuid,
    ciphertext = Base64.Default.encode(message),
    createdAt = createdAt,
    departedMemberUuids = departedMemberUuids,
)

/** [this] once it shows [version], as `GroupDao.updateInformation` leaves it. */
internal fun GroupEntity.showing(version: GroupVersion) = GroupEntity(
    uuid = uuid,
    title = version.fields.title,
    description = version.fields.description,
    currency = currency,
    infoRevision = version.revision,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    keyGeneration = keyGeneration,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
    hasTakenInExpenses = hasTakenInExpenses,
    isAwaitingOwner = isAwaitingOwner,
)
