package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import mls.processGroupMessageIn
import uniffi.lib_duckcount.CommitPolicy
import uniffi.lib_duckcount.MlsClient

/**
 * The content key of the epoch the group [mlsGroupId], the one the server holds as [groupUuid], is
 * in on this client's device, as the library derives it for every member in that epoch, see
 * `MlsGroup.contentKey`: what the group's information and expenses are sealed under in it.
 */
internal fun MlsClient.contentKeyIn(mlsGroupId: ByteArray, groupUuid: String): ByteArray =
    loadGroup(mlsGroupId = mlsGroupId).use { it.contentKey(groupUuid = groupUuid) }

/** The epoch the group [mlsGroupId] is in on this client's device. */
internal fun MlsClient.epochIn(mlsGroupId: ByteArray): Long =
    loadGroup(mlsGroupId = mlsGroupId).use { it.epoch().toLong() }

/**
 * Processes [message] in the group [mlsGroupId], the one the server holds as [groupUuid], as every
 * member's device processes one the server stored as removing [departedUuids], see
 * [mls.processGroupMessageIn]: Commits taken from [creatorUuid] whatever they change, and from
 * anybody else when they remove exactly those.
 */
internal suspend fun MlsClient.processUnderPolicy(
    mlsGroupId: ByteArray,
    message: ByteArray,
    groupUuid: String,
    creatorUuid: String,
    departedUuids: List<String> = emptyList(),
) = processGroupMessageIn(
    mlsGroupId = mlsGroupId,
    message = message,
    groupUuid = groupUuid,
    policy = CommitPolicy(creatorUuid = creatorUuid, departedUuids = departedUuids),
)

/** The key this group holds, as the generation of its keys it is, as the database keeps it. */
internal fun GroupEntity.currentKey() =
    GroupKeyEntity(groupUuid = uuid, generation = keyGeneration, groupKey = groupKey)
