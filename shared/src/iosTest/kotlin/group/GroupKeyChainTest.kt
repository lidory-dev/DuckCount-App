package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * What a device gets of the generations of a group's key, see [GroupKeyChain]: the four keys Alice
 * gave the group, one with its creation and one with each of three rotations, and the links her
 * rotations stored with the server, each sealed as [GroupMembershipSender] seals it. The database
 * and the server are stood in for, the sealing is the library's own.
 */
class GroupKeyChainTest {

    /** The key of each generation, by generation. */
    private val keys = (1L..4L).associateWith { generateSymmetricEncryptionKey() }

    /** The links the server lists, the second generation's to the fourth's, and whether it answers. */
    private val links = (2L..4L).mapTo(mutableListOf()) { linkAt(generation = it) }
    private var isServerReachable = true
    private var linksAsked = 0

    /** The group as this device holds it, and the generations of its key it holds. */
    private var group: GroupEntity? = groupOf(keyGeneration = 4)
    private val held = mutableMapOf(4L to keys.getValue(4))

    /** What the chain got back and kept, in order. */
    private val recovered = mutableListOf<GroupKeyEntity>()

    private val chain = GroupKeyChain(
        keyLinks = {
            linksAsked++
            if (isServerReachable) Result.Success(links.toList()) else Result.Error(NetworkError.NO_INTERNET)
        },
        group = { groupUuid -> group?.takeIf { it.uuid == groupUuid } },
        heldKeys = { groupUuid ->
            held.map { (generation, key) ->
                GroupKeyEntity(groupUuid = groupUuid, generation = generation, groupKey = key)
            }
        },
        storeRecovered = { _, keys ->
            recovered += keys
            keys.forEach { held.getOrPut(it.generation) { it.groupKey } }
        },
    )

    /** A member added after two rotations, handed the current key alone, see [welcomePayloadOf]. */
    @Test
    fun `a newcomer holding the third generation gets the first back through the chain`() = runBlocking<Unit> {
        group = groupOf(keyGeneration = 3)
        held.clear()
        held[3] = keys.getValue(3)
        links.removeAll { it.generation == 4L }

        assertContentEquals(keys.getValue(1), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 1)).data)
        assertEquals(listOf(2L, 1L), recovered.map { it.generation })
        assertContentEquals(keys.getValue(2), recovered.first().groupKey)
    }

    /** Most generations of most groups: a member who held them all from the start never asks. */
    @Test
    fun `a generation held is given without asking the server`() = runBlocking<Unit> {
        assertContentEquals(keys.getValue(4), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 4)).data)

        assertEquals(0, linksAsked)
    }

    @Test
    fun `every key got back on the way down is kept and none asked for twice`() = runBlocking<Unit> {
        chain.keyOf(GROUP, 1)

        assertContentEquals(keys.getValue(3), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 3)).data)
        assertContentEquals(keys.getValue(2), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 2)).data)
        assertEquals(1, linksAsked)
    }

    /**
     * As for a member removed at the second and added back at the fourth, who holds both: the first
     * is the second's link away, and the links after it are not needed, one the server spoiled there
     * changing nothing. Walked down from the newest held, it would be read, and fail.
     */
    @Test
    fun `the chain is walked down from the nearest generation held after the one asked for`() = runBlocking<Unit> {
        held[2] = keys.getValue(2)
        listAt(generation = 3, link = linkAt(generation = 2).copy(generation = 3))

        assertContentEquals(keys.getValue(1), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 1)).data)
        assertEquals(listOf(1L), recovered.map { it.generation })
    }

    /** Holding the first two and the fourth: the third is the fourth's link away, and the spoiled link below it is not read. */
    @Test
    fun `the chain is walked down no further than the generation asked for`() = runBlocking<Unit> {
        held[1] = keys.getValue(1)
        held[2] = keys.getValue(2)
        listAt(generation = 3, link = linkAt(generation = 2).copy(generation = 3))

        assertContentEquals(keys.getValue(3), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 3)).data)
        assertEquals(listOf(3L), recovered.map { it.generation })
    }

    /** As the server moving one group's link to another would have it. */
    @Test
    fun `a link sealed for another group does not open`() = runBlocking<Unit> {
        listAt(generation = 4, link = linkAt(generation = 4, sealedFor = OTHER_GROUP))

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), chain.keyOf(GROUP, 3))
        assertTrue(recovered.isEmpty())
    }

    /**
     * As the server listing a link at another place in the chain than its own would have it: the
     * key it holds would be taken for that of another generation.
     */
    @Test
    fun `a link sealed for another generation does not open`() = runBlocking<Unit> {
        val misplaced = assertNotNull(
            sealKeyLink(previousKey = keys.getValue(3), groupKey = keys.getValue(4), groupUuid = GROUP, generation = 5),
        )

        listAt(generation = 4, link = GroupKeyLinkDto(generation = 4, previousKey = misplaced))

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), chain.keyOf(GROUP, 3))
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun `a link opens under the key and the place it was sealed for alone`() {
        val link = linkAt(generation = 3).previousKey

        assertContentEquals(
            keys.getValue(2),
            link.openKeyLink(groupKey = keys.getValue(3), groupUuid = GROUP, generation = 3),
        )
        assertNull(link.openKeyLink(groupKey = keys.getValue(3), groupUuid = GROUP, generation = 2))
        assertNull(link.openKeyLink(groupKey = keys.getValue(3), groupUuid = OTHER_GROUP, generation = 3))
        assertNull(link.openKeyLink(groupKey = keys.getValue(4), groupUuid = GROUP, generation = 3))
    }

    /** Whatever the server holds back, what came before it is kept, and the rest asked for again later. */
    @Test
    fun `a link missing fails and what the links led to before it is kept`() = runBlocking<Unit> {
        links.removeAll { it.generation == 2L }

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), chain.keyOf(GROUP, 1))
        assertEquals(listOf(3L, 2L), recovered.map { it.generation })
    }

    @Test
    fun `a link opening to something that is no group key does not open`() = runBlocking<Unit> {
        val sealed = assertNotNull(
            sealKeyLink(previousKey = ByteArray(16), groupKey = keys.getValue(4), groupUuid = GROUP, generation = 4),
        )

        listAt(generation = 4, link = GroupKeyLinkDto(generation = 4, previousKey = sealed))

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), chain.keyOf(GROUP, 3))
    }

    /** The chain leads back alone: a later generation is the creator's to hand over. */
    @Test
    fun `a generation after every one held is not asked for`() = runBlocking<Unit> {
        assertEquals(Result.Error(GroupKeyChainError.NotHeld), chain.keyOf(GROUP, 5))

        assertEquals(0, linksAsked)
    }

    /** Removed, or left: the server lists the chain to current members alone. */
    @Test
    fun `a group the user is no longer in asks for nothing and gives what it holds`() = runBlocking<Unit> {
        group = groupOf(keyGeneration = 4, isLeft = true)

        assertEquals(Result.Error(GroupKeyChainError.NotMember), chain.keyOf(GROUP, 3))
        assertContentEquals(keys.getValue(4), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 4)).data)
        assertEquals(0, linksAsked)
    }

    @Test
    fun `a group this device does not hold has no key`() = runBlocking<Unit> {
        group = null

        assertEquals(Result.Error(GroupKeyChainError.NotHeld), chain.keyOf(GROUP, 1))
        assertEquals(0, linksAsked)
    }

    @Test
    fun `a server that cannot be reached is reported and nothing kept`() = runBlocking<Unit> {
        isServerReachable = false

        assertEquals(Result.Error(GroupKeyChainError.Network(NetworkError.NO_INTERNET)), chain.keyOf(GROUP, 1))
        assertTrue(recovered.isEmpty())
    }

    /** Has the server list [link] in place of the one at [generation]. */
    private fun listAt(generation: Long, link: GroupKeyLinkDto) {
        links[links.indexOfFirst { it.generation == generation }] = link
    }

    /**
     * The link at [generation], the key before it sealed under its own, for the group [sealedFor],
     * as the server lists it for [GROUP].
     */
    private fun linkAt(generation: Long, sealedFor: String = GROUP) = GroupKeyLinkDto(
        generation = generation,
        previousKey = assertNotNull(
            sealKeyLink(
                previousKey = keys.getValue(generation - 1),
                groupKey = keys.getValue(generation),
                groupUuid = sealedFor,
                generation = generation,
            ),
        ),
    )

    /** [GROUP] as this device holds it, Alice's, at [keyGeneration]. */
    private fun groupOf(keyGeneration: Long, isLeft: Boolean = false) = GroupEntity(
        uuid = GROUP,
        title = "Ski trip",
        description = "",
        currency = CurrencyCatalog.defaultCurrency,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = ALICE,
        mlsGroupId = GROUP.encodeToByteArray(),
        groupKey = keys.getValue(keyGeneration),
        keyGeneration = keyGeneration,
        isBackedUp = true,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}
