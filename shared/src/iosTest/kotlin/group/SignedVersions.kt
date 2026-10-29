package group

import database.data.local.entity.GroupEntity
import database.data.remote.EncryptedFieldDto
import uniffi.lib_duckcount.MlsClient
import kotlin.test.assertNotNull

/**
 * These fields as [revision] of the group [groupUuid], signed by [creator] as their device signs
 * every version of a group of theirs, see [signedAs], and sealed under [groupKey] for that group,
 * see [GroupVersion.seal]: what the server holds of it once [creator] created or edited it.
 */
internal fun GroupFields.sealedAs(
    creator: MlsClient,
    groupKey: ByteArray,
    groupUuid: String,
    revision: Long = FIRST_GROUP_REVISION,
): SealedGroupVersion {
    val version = assertNotNull(signedAs(client = creator, groupUuid = groupUuid, revision = revision))

    return assertNotNull(version.seal(groupKey = groupKey, groupUuid = groupUuid))
}

/**
 * The group [uuid] as the server lists it, owned by [ownerUuid] and created by [creatorUuid], with
 * [memberUuids], [version] of its information, and the caller's copy of its key, [keyCopy], if any.
 */
internal fun listedGroupOf(
    uuid: String,
    ownerUuid: String,
    version: SealedGroupVersion,
    memberUuids: List<String>,
    keyCopy: EncryptedFieldDto? = null,
    creatorUuid: String = ownerUuid,
) = GroupDto(
    uuid = uuid,
    ownerUuid = ownerUuid,
    creatorUuid = creatorUuid,
    title = version.fields.title,
    description = version.fields.description,
    currency = version.fields.currency,
    revision = version.revision,
    signature = version.signature,
    members = memberUuids.map { GroupMemberDto(userUuid = it) },
    keyCopy = keyCopy,
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
 * The version a key rotation, [body], puts on the server in place of this one: its fields and
 * signature, the revision staying as it was, see [GroupMembershipSender].
 */
internal fun SealedGroupVersion.rotatedBy(body: RotateGroupKeyBody) = SealedGroupVersion(
    fields = SealedGroupFields(title = body.title, description = body.description, currency = body.currency),
    revision = revision,
    signature = body.signature,
)

/**
 * This version as the group's history holds it, see [GroupHistoryVersionDto]: its fields but the
 * currency, which the history does not hold, stored by the server at [revisedAt].
 */
internal fun SealedGroupVersion.inHistory(revisedAt: String = "2026-10-29T08:15:42.317Z") = GroupHistoryVersionDto(
    revision = revision,
    title = fields.title,
    description = fields.description,
    signature = signature,
    revisedAt = revisedAt,
)

/**
 * The versions a key rotation, [body], leaves the server holding in the group's history, of
 * these, as the API takes it: each one it carries in place of the one at the same revision, when
 * there is one, the time it was stored at kept, and none it does not carry.
 */
internal fun List<GroupHistoryVersionDto>.rotatedBy(body: RotateGroupKeyBody) = mapNotNull { held ->
    body.previous.firstOrNull { it.revision == held.revision }?.let { carried ->
        held.copy(title = carried.title, description = carried.description, signature = carried.signature)
    }
}

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
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
