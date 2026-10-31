package group

import database.data.local.dao.GroupDao
import database.data.local.entity.GroupKeyEntity
import database.data.remote.EncryptedFieldDto
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Sees to it that the server holds, at every generation a Commit read took a group to, a link
 * leading back to the key this device holds of the generation before, see [GroupKeyChain]: those
 * whose key owes it, see [GroupKeyEntity.isLinkOwed].
 *
 * The committer stores a link with their Commit, see [AddGroupMembersBody.previousKey], and is a
 * member like any: theirs is only their word, and a member who joins later, holding the newer key
 * alone, would read nothing sealed before it should it lead nowhere. So each member reaching the
 * epoch looks at the candidates the server lists there, see [GroupApi.keyLinks], and publishes its
 * own when none opens to the key it holds, see [GroupApi.publishKeyLink]: one honest member's link
 * is all a newcomer needs, the chain trying every candidate.
 *
 * Run by the group sync once every group is caught up, see [GroupSync], under its lock, and with no
 * read of the vault: the keys are the database's. Each key is checked once, and one whose group
 * could not be asked for its links, or whose link could not be published, is checked again by the
 * next sync.
 *
 * [keysOwingLinks], [heldKeys] and [markLinkSeen] are [GroupDao.getKeysOwingLinks],
 * [GroupDao.getKeys] and [GroupDao.markLinkSeen], [keyLinks] and [publishKeyLink] are
 * [GroupApi.keyLinks] and [GroupApi.publishKeyLink], or what a test stands in for any of them.
 */
class GroupKeyLinks internal constructor(
    private val keysOwingLinks: suspend () -> List<GroupKeyEntity>,
    private val heldKeys: suspend (groupUuid: String) -> List<GroupKeyEntity>,
    private val keyLinks: suspend (groupUuid: String) -> Result<List<GroupKeyLinkDto>, NetworkError>,
    private val publishKeyLink: suspend (
        groupUuid: String,
        generation: Long,
        previousKey: EncryptedFieldDto,
    ) -> EmptyResult<NetworkError>,
    private val markLinkSeen: suspend (groupUuid: String, generation: Long) -> Unit,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
    ) : this(
        keysOwingLinks = groupDao::getKeysOwingLinks,
        heldKeys = groupDao::getKeys,
        keyLinks = groupApi::keyLinks,
        publishKeyLink = groupApi::publishKeyLink,
        markLinkSeen = { groupUuid, generation -> groupDao.markLinkSeen(groupUuid = groupUuid, generation = generation) },
    )

    /**
     * Checks the links of every key owing it, see [GroupKeyLinks], and returns the first failure: a
     * group that fails is left for the next sync, and the next group is seen to all the same. A
     * link not published stops its group there, the server failing for the next one just the same.
     */
    suspend fun check(): EmptyResult<GroupSyncError> {
        val owing = keysOwingLinks().groupBy { it.groupUuid }

        // Most syncs read no Commit.
        if (owing.isEmpty()) return Result.Success(Unit)

        var failure: GroupSyncError? = null

        for ((groupUuid, keys) in owing) {
            val held = heldKeys(groupUuid).associate { it.generation to it.groupKey }

            val links = when (val listed = keyLinks(groupUuid)) {
                is Result.Success -> listed.data.groupBy { it.generation }

                is Result.Error -> {
                    failure = failure ?: GroupSyncError.Network(listed.error)

                    continue
                }
            }

            for (key in keys.sortedBy { it.generation }) {
                val generation = key.generation

                // The key before is one this device never derived, a Welcome's it could not read
                // say: there is nothing to hold the links to, nor to publish.
                val previous = held[generation - 1]

                val isLinked = previous == null || links[generation].orEmpty().any { link ->
                    link.previousKey
                        .openKeyLink(groupKey = key.groupKey, groupUuid = groupUuid, generation = generation)
                        ?.contentEquals(previous) == true
                }

                if (!isLinked) {
                    val link = checkNotNull(
                        sealKeyLink(
                            previousKey = checkNotNull(previous),
                            groupKey = key.groupKey,
                            groupUuid = groupUuid,
                            generation = generation,
                        ),
                    ) { "A key MLS derived seals." }

                    val published = publishKeyLink(groupUuid, generation, link)

                    if (published is Result.Error) {
                        failure = failure ?: GroupSyncError.Network(published.error)

                        break
                    }
                }

                markLinkSeen(groupUuid, generation)
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }
}
