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
 * The generation of the key a group is created with, see [GroupEntity.keyGeneration]: the epoch
 * its creation leaves its MLS group at. Each Commit the server stores adds one.
 */
internal const val FIRST_KEY_GENERATION = 1L

/** The length of every group key, the content key MLS derives for each epoch, see [mls.EpochKey]. */
internal const val GROUP_KEY_LENGTH = 32

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
 * The key of [generation] that [links], every candidate the server lists for the group
 * [groupUuid], see [GroupKeyLinkDto], lead back to from [from], a generation after it this device
 * holds, with every key on the way down, the one of [generation] last; or null when none of them
 * leads there, see [GroupKeyChain].
 *
 * Every candidate at a generation is tried, in the order listed, the walk going on down from each
 * key one opens to, and back up to the next candidate when it leads nowhere. Only what [opens] says
 * opens, the content the caller is after, tells the key of [generation] from any other: a member
 * can publish, at any generation past the first, a link opening under the key after it to a key of
 * their own, and below it links opening under that one, so that a key leading on down proves
 * nothing of itself. Nor do the keys on the way down, a member who held the key below one of them
 * able to lead the walk there through one of their own, which the caller keeps as no more than
 * what the links said, see [GroupKeyChain.keyOf].
 */
internal fun recoverKeys(
    groupUuid: String,
    generation: Long,
    from: GroupKeyGeneration,
    links: List<GroupKeyLinkDto>,
    opens: (key: ByteArray) -> Boolean,
): List<GroupKeyGeneration>? {
    val candidates = links.groupBy { it.generation }

    // The keys from the generation before [linkGeneration] down to [generation], led back to from [key].
    fun walk(key: ByteArray, linkGeneration: Long): List<GroupKeyGeneration>? {
        val keyGeneration = linkGeneration - 1

        for (link in candidates[linkGeneration].orEmpty()) {
            val previous = link.previousKey.openKeyLink(groupKey = key, groupUuid = groupUuid, generation = linkGeneration)
                ?: continue

            val step = GroupKeyGeneration(generation = keyGeneration, key = previous)

            if (keyGeneration == generation) {
                if (opens(previous)) return listOf(step) else continue
            }

            walk(key = previous, linkGeneration = keyGeneration)?.let { return listOf(step) + it }
        }

        return null
    }

    return walk(key = from.key, linkGeneration = from.generation)
}

/**
 * Gives the key of any generation of a group's keys this device can have, see [GroupKeyEntity]:
 * what the group's expenses, and each version of its information, are opened with, each sealed
 * under the generation it was written in.
 *
 * A group's key is the content key MLS derives in each of its epochs, one generation per epoch, see
 * [mls.EpochKey], which no member chooses, and nothing sealed under one is sealed again under the
 * next. MLS derives the key of the epoch a member is in and no other, so a member added later, or a
 * device that got a key back from the user's copy after a log-out, see [GroupKeyCopies], derives
 * none before it. Every Commit the server stores carries instead the key of the epoch it is built
 * in sealed under the key of the epoch it leads to, see [AddGroupMembersBody.previousKey], which the
 * server keeps as a link of the chain back through the group's keys and lists to its current
 * members, see [GroupApi.keyLinks]. Whoever holds a generation opens its link, and gets the one
 * before, and so on down to the first: everything sealed before it is theirs to read. Nothing in
 * the chain leads forward, so a member removed by a Commit, who stays in the epoch it ended,
 * reads nothing sealed after it.
 *
 * The committer of each Commit is a member like any, though, and the link it stored is only its
 * word. So every member reaching an epoch checks that a link the server lists there leads back to
 * the key it holds, and publishes its own when none does, see [GroupSync]: the server keeps one per
 * member and generation, and lists them all, and the walk down tries each, taking a key for the
 * generation asked for only once what the caller is after opens under it, see [recoverKeys].
 *
 * Keys are got back as they are asked for, rather than as the group is joined: most generations of
 * most groups are never asked for, and a member holding them all from the start, as most do, never
 * asks the server anything. What is got back is kept, see [GroupDao.insertRecoveredKeys], every key
 * on the way down included, so that each link is opened once, but as what the links said, see
 * [GroupKeyEntity.isRecovered]: such a key gives what opens under it, and for anything else of its
 * generation the chain is walked again, down from a key MLS or the user's copy gave. A member's
 * key on the way down, or one a version of their own opens under, then keeps nothing the members
 * sealed from being read.
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
     * holds, or else the one the links lead back to from the nearest generation after it that MLS
     * or the user's copy gave it, under which [opens] says what the caller is after opens, see
     * [recoverKeys]. One held that the chain gave is given only once [opens] says so, see
     * [GroupKeyEntity.isRecovered], and given still should the chain lead to none it does: what the
     * caller is after is then sealed under no key the members hold.
     *
     * Only the server is asked, and only for a generation this device holds no key of that opens
     * it, below one it holds, of a group the user is still in: the server lists the chain to
     * current members alone, and a generation past every one held is the group's Commits' to give,
     * see [GroupKeyChainError.NotHeld]. Nothing is kept of a walk that did not get there, and of one
     * that did, nothing in place of a key held, see [GroupDao.insertRecoveredKeys].
     */
    internal suspend fun keyOf(
        groupUuid: String,
        generation: Long,
        opens: (key: ByteArray) -> Boolean,
    ): Result<ByteArray, GroupKeyChainError> {
        val group = group(groupUuid) ?: return Result.Error(GroupKeyChainError.NotHeld)

        val held = heldKeys(groupUuid)

        val heldKey = held.firstOrNull { it.generation == generation }

        if (heldKey != null && (!heldKey.isRecovered || opens(heldKey.groupKey))) return Result.Success(heldKey.groupKey)

        val recovered = when (val walked = walkDown(group = group, generation = generation, held = held, opens = opens)) {
            is Result.Success -> walked.data

            // Sealed under no key the chain leads to: what was asked for is given what is held.
            is Result.Error -> return if (heldKey != null && walked.error !is GroupKeyChainError.Network) {
                Result.Success(heldKey.groupKey)
            } else {
                walked
            }
        }

        storeRecovered(
            groupUuid,
            recovered.map {
                GroupKeyEntity(groupUuid = groupUuid, generation = it.generation, groupKey = it.key, isRecovered = true)
            },
        )

        return Result.Success(recovered.last().key)
    }

    /**
     * The keys the links lead back to, down to [generation] of the keys of [group], of which this
     * device holds [held], from the nearest generation after it that the chain did not give, see
     * [keyOf].
     */
    private suspend fun walkDown(
        group: GroupEntity,
        generation: Long,
        held: List<GroupKeyEntity>,
        opens: (key: ByteArray) -> Boolean,
    ): Result<List<GroupKeyGeneration>, GroupKeyChainError> {
        // Every generation between the two is one this device lacks, or holds as the chain gave it.
        val nearestAfter = held.filter { it.generation > generation && !it.isRecovered }.minByOrNull { it.generation }
            ?: return Result.Error(GroupKeyChainError.NotHeld)

        if (group.isLeft) return Result.Error(GroupKeyChainError.NotMember)

        val links = when (val listed = keyLinks(group.uuid)) {
            is Result.Success -> listed.data
            is Result.Error -> return Result.Error(GroupKeyChainError.Network(listed.error))
        }

        return recoverKeys(
            groupUuid = group.uuid,
            generation = generation,
            from = GroupKeyGeneration(generation = nearestAfter.generation, key = nearestAfter.groupKey),
            links = links,
            opens = opens,
        )?.let { Result.Success(it) } ?: Result.Error(GroupKeyChainError.Unreadable)
    }
}
