package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.remote.EncryptedFieldDto
import util.NetworkError
import util.Result
import vault.sealBound
import vault.unsealBound

/**
 * The generation of the key a group is created with, see [GroupEntity.keyGeneration]. Each key
 * rotation of its creator's adds one, see [GroupMembershipSender].
 */
internal const val FIRST_KEY_GENERATION = 1L

/** One generation of a group's key: the [key], 32 bytes, and which [generation] of the group's keys it is. */
internal class GroupKeyGeneration(val generation: Long, val key: ByteArray)

/**
 * What the link at [generation] of the chain back through the keys of the group [groupUuid] is
 * bound to, see [sealBound]: the key before it sealed under the key of [generation], see
 * [GroupKeyChain].
 *
 * Naming the generation binds each link to its place in the chain: without it, the server could
 * serve one link at the place of another, and the key it holds would be taken for that of another
 * generation, under which nothing it was sealed for opens. Naming the group is the same second
 * check its fields have, see [GroupFields.seal].
 */
private fun linkContextOf(groupUuid: String, generation: Long) = "group:$groupUuid:key:$generation"

/**
 * Seals [previousKey], the key of the group [groupUuid] before [generation], under [groupKey], the
 * one of [generation], as the link at [generation] of the chain back through its keys, see
 * [GroupKeyChain], or returns null when [groupKey] is not a 32-byte key.
 */
internal fun sealKeyLink(
    previousKey: ByteArray,
    groupKey: ByteArray,
    groupUuid: String,
    generation: Long,
): EncryptedFieldDto? =
    sealBound(
        key = groupKey,
        context = linkContextOf(groupUuid = groupUuid, generation = generation),
        plaintext = previousKey,
    )

/**
 * Opens this link, at [generation] of the chain back through the keys of the group [groupUuid],
 * with [groupKey], the key of [generation], and returns the key before it, or null when it does not
 * open as that link of that group under that key, see [linkContextOf], or opens to something that
 * is no group key.
 */
internal fun EncryptedFieldDto.openKeyLink(groupKey: ByteArray, groupUuid: String, generation: Long): ByteArray? =
    unsealBound(key = groupKey, context = linkContextOf(groupUuid = groupUuid, generation = generation), content = this)
        ?.takeIf { it.size == GROUP_KEY_LENGTH }

/**
 * Gives the key of any generation of a group's keys this device can have, see [GroupKeyEntity]:
 * what the group's expenses are opened with, each sealed under the generation it was written in.
 *
 * A group's key is replaced at every removal, see [GroupMembershipSender], and nothing sealed under
 * the old one is sealed again: the creator's device stores instead, with the rotation, the old key
 * sealed under the new one, see [RotateGroupKeyBody.previousKey], which the server keeps as a link
 * of the chain back through the group's keys and lists to its current members, see
 * [GroupApi.keyLinks]. Whoever holds a generation opens its link, and gets the one before, and so on
 * down to the first: a member added after a rotation, handed the current key alone, see
 * [welcomePayloadOf], or a device that got the key back from the user's copy after a log-out, see
 * [GroupKeyCopies], reads everything sealed before it. Nothing in the chain leads forward, so a
 * member removed before a rotation, who never got its key, reads nothing sealed after it.
 *
 * Keys are got back as they are asked for, rather than as the group is joined: most generations of
 * most groups are never asked for, and a member holding them all from the start, as most do, never
 * asks the server anything. What is got back is kept, see [GroupDao.insertRecoveredKeys], every key
 * on the way down included, so that each link is opened once.
 *
 * Under no lock, as [GroupHistory] reads: all it writes is generations this device lacked, none in
 * place of one it holds, see [GroupDao.insertRecoveredKeys], and the sync holds the lock across its
 * requests to the server, which a screen opening a group would wait on.
 *
 * [keyLinks] is [GroupApi.keyLinks], [group] is [GroupDao.get], [heldKeys] is [GroupDao.getKeys]
 * and [storeRecovered] is [GroupDao.insertRecoveredKeys], or what a test stands in for any of them.
 */
class GroupKeyChain internal constructor(
    private val keyLinks: suspend (groupUuid: String) -> Result<List<GroupKeyLinkDto>, NetworkError>,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val heldKeys: suspend (groupUuid: String) -> List<GroupKeyEntity>,
    private val storeRecovered: suspend (groupUuid: String, keys: List<GroupKeyEntity>) -> Unit,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
    ) : this(
        keyLinks = groupApi::keyLinks,
        group = groupDao::get,
        heldKeys = groupDao::getKeys,
        storeRecovered = groupDao::insertRecoveredKeys,
    )

    /**
     * The key of the generation [generation] of the group [groupUuid]'s keys, the one this device
     * holds, or else the one the links lead back to from the nearest generation after it that it
     * holds, see [GroupKeyChain].
     *
     * Only the server is asked, and only for a generation this device lacks, below one it holds,
     * of a group the user is still in: the server lists the chain to current members alone, and a
     * generation past every one held is the creator's to hand over, see [GroupKeyChainError.NotHeld].
     * Whatever the links led to before one did not open is kept all the same.
     */
    suspend fun keyOf(groupUuid: String, generation: Long): Result<ByteArray, GroupKeyChainError> {
        val group = group(groupUuid) ?: return Result.Error(GroupKeyChainError.NotHeld)

        val held = heldKeys(groupUuid).associate { it.generation to it.groupKey }

        held[generation]?.let { return Result.Success(it) }

        // Every generation between the two is one this device lacks too, got back on the way down.
        val nearestAfter = held.keys.filter { it > generation }.minOrNull()
            ?: return Result.Error(GroupKeyChainError.NotHeld)

        if (group.isLeft) return Result.Error(GroupKeyChainError.NotMember)

        val links = when (val listed = keyLinks(groupUuid)) {
            is Result.Success -> listed.data.associateBy { it.generation }
            is Result.Error -> return Result.Error(GroupKeyChainError.Network(listed.error))
        }

        val recovered = mutableListOf<GroupKeyEntity>()
        var key = held.getValue(nearestAfter)

        for (linkGeneration in nearestAfter downTo generation + 1) {
            key = links[linkGeneration]
                ?.previousKey
                ?.openKeyLink(groupKey = key, groupUuid = groupUuid, generation = linkGeneration)
                ?: break

            recovered += GroupKeyEntity(groupUuid = groupUuid, generation = linkGeneration - 1, groupKey = key)
        }

        if (recovered.isNotEmpty()) storeRecovered(groupUuid, recovered)

        return if (recovered.lastOrNull()?.generation == generation) {
            Result.Success(key)
        } else {
            Result.Error(GroupKeyChainError.Unreadable)
        }
    }
}
