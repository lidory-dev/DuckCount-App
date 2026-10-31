package group

import database.data.local.entity.GroupKeyEntity
import database.data.remote.EncryptedFieldDto
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * What a member's device does of the links at the epochs a Commit read took a group to, see
 * [GroupKeyLinks]: the keys of the group's three epochs its device derived, the second and third
 * owing a look at their links, and the candidates the server lists, a committer's or Mallory's.
 * The database and the server are stood in for, the sealing is the library's own.
 */
class GroupKeyLinksTest {

    /** The key of each generation, by generation, as this device derived them. */
    private val keys = (1L..3L).associateWith { generateSymmetricEncryptionKey() }

    /** The keys this device holds, by group, those owing a look at their links marked so. */
    private val held = mutableMapOf(
        GROUP to keys.map { (generation, key) ->
            GroupKeyEntity(groupUuid = GROUP, generation = generation, groupKey = key, isLinkOwed = generation > 1)
        }.toMutableList(),
    )

    /** The candidates the server lists, by group, whether it answers, and what it was sent. */
    private val links = mutableMapOf(GROUP to mutableListOf(linkAt(generation = 2), linkAt(generation = 3)))
    private var isListingReachable = true
    private var publishAnswer: EmptyResult<NetworkError> = Result.Success(Unit)
    private val published = mutableListOf<Pair<Long, EncryptedFieldDto>>()

    private val keyLinks = GroupKeyLinks(
        keysOwingLinks = { held.values.flatten().filter { it.isLinkOwed } },
        heldKeys = { groupUuid -> held[groupUuid].orEmpty() },
        keyLinks = { groupUuid ->
            if (isListingReachable) Result.Success(links[groupUuid].orEmpty().toList()) else Result.Error(NetworkError.NO_INTERNET)
        },
        publishKeyLink = { _, generation, previousKey ->
            published += generation to previousKey
            publishAnswer
        },
        markLinkSeen = { groupUuid, generation ->
            held[groupUuid] = held.getValue(groupUuid).mapTo(mutableListOf()) {
                if (it.generation == generation) it.seen() else it
            }
        },
    )

    /** The committers' links lead back to the keys this device holds: nothing to publish. */
    @Test
    fun `a key whose committer's link leads back to the key before is seen and nothing published`() =
        runBlocking<Unit> {
            assertEquals(Result.Success(Unit), keyLinks.check())

            assertTrue(published.isEmpty())
            assertTrue(held.getValue(GROUP).none { it.isLinkOwed })
        }

    /**
     * Mallory committed into the third epoch and stored a link leading to a key of her own: this
     * device publishes its own there, which a member joining later walks down instead.
     */
    @Test
    fun `a key whose links all lead elsewhere has this device publish its own`() = runBlocking<Unit> {
        links.getValue(GROUP)[1] = GroupKeyLinkDto(
            generation = 3,
            publisherUuid = MALLORY,
            previousKey = sealed(previousKey = generateSymmetricEncryptionKey(), generation = 3),
        )

        assertEquals(Result.Success(Unit), keyLinks.check())

        val (generation, link) = published.single()

        assertEquals(3L, generation)
        assertContentEquals(keys.getValue(2), link.openKeyLink(groupKey = keys.getValue(3), groupUuid = GROUP, generation = 3))
        assertTrue(held.getValue(GROUP).none { it.isLinkOwed })
    }

    /** One honest candidate among others is all a newcomer needs, the chain trying every one. */
    @Test
    fun `a key with one link among others leading back is seen and nothing published`() = runBlocking<Unit> {
        links.getValue(GROUP).add(
            0,
            GroupKeyLinkDto(
                generation = 2,
                publisherUuid = MALLORY,
                previousKey = sealed(previousKey = generateSymmetricEncryptionKey(), generation = 2),
            ),
        )

        assertEquals(Result.Success(Unit), keyLinks.check())

        assertTrue(published.isEmpty())
    }

    /** None stored at all, as the server holding the committer's back. */
    @Test
    fun `a key with no link at all has this device publish its own`() = runBlocking<Unit> {
        links.getValue(GROUP).removeAll { it.generation == 2L }

        assertEquals(Result.Success(Unit), keyLinks.check())

        assertEquals(listOf(2L), published.map { it.first })
    }

    /** The next sync looks again. */
    @Test
    fun `a link the server could not be asked about stays owed for the next sync`() = runBlocking<Unit> {
        isListingReachable = false

        assertEquals(Result.Error(GroupSyncError.Network(NetworkError.NO_INTERNET)), keyLinks.check())

        assertEquals(listOf(2L, 3L), held.getValue(GROUP).filter { it.isLinkOwed }.map { it.generation })
    }

    @Test
    fun `a link that could not be published stays owed with every one after it`() = runBlocking<Unit> {
        links.getValue(GROUP).clear()
        publishAnswer = Result.Error(NetworkError.SERVER_ERROR)

        assertEquals(Result.Error(GroupSyncError.Network(NetworkError.SERVER_ERROR)), keyLinks.check())

        assertEquals(listOf(2L), published.map { it.first })
        assertEquals(listOf(2L, 3L), held.getValue(GROUP).filter { it.isLinkOwed }.map { it.generation })
    }

    /** A Welcome's epoch this device could not derive the key of, say: nothing to hold the links to. */
    @Test
    fun `a key whose generation before is not held is seen with nothing published`() = runBlocking<Unit> {
        held[OTHER_GROUP] = mutableListOf(
            GroupKeyEntity(groupUuid = OTHER_GROUP, generation = 5, groupKey = generateSymmetricEncryptionKey(), isLinkOwed = true),
        )

        assertEquals(Result.Success(Unit), keyLinks.check())

        assertTrue(published.isEmpty())
        assertTrue(held.getValue(OTHER_GROUP).none { it.isLinkOwed })
    }

    @Test
    fun `nothing owed asks the server nothing`() = runBlocking<Unit> {
        held[GROUP] = held.getValue(GROUP).mapTo(mutableListOf()) { it.seen() }
        isListingReachable = false

        assertEquals(Result.Success(Unit), keyLinks.check())
    }

    /** The committer's link at [generation], as its Commit stored it. */
    private fun linkAt(generation: Long) = GroupKeyLinkDto(
        generation = generation,
        publisherUuid = ALICE,
        previousKey = sealed(previousKey = keys.getValue(generation - 1), generation = generation),
    )

    /** [previousKey] sealed under the key of [generation] as its link. */
    private fun sealed(previousKey: ByteArray, generation: Long) = checkNotNull(
        sealKeyLink(previousKey = previousKey, groupKey = keys.getValue(generation), groupUuid = GROUP, generation = generation),
    )
}

/** This key once its links are seen to, as `GroupDao.markLinkSeen` leaves it. */
private fun GroupKeyEntity.seen() = GroupKeyEntity(groupUuid = groupUuid, generation = generation, groupKey = groupKey)
