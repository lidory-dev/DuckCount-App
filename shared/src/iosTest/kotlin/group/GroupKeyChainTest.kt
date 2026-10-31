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
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * What a device gets of the generations of a group's key, see [GroupKeyChain]: the keys of the
 * group's four epochs, and the links each Commit stored with the server, Alice's, each sealed as
 * [GroupMembershipChanger] seals it, with whatever candidates a test has its members publish
 * beside them, Mallory's leading nowhere. The database and the server are stood in for, the
 * sealing is the library's own.
 */
class GroupKeyChainTest {

    /** The key of each generation, by generation. */
    private val keys = (1L..4L).associateWith { generateSymmetricEncryptionKey() }

    /**
     * What is sealed under each generation's key: what a caller opening something of that generation
     * holds, which tells its key from any other, see [recoverKeys].
     */
    private val content = keys.mapValues { (_, key) ->
        assertNotNull(GroupFields(title = "Ski trip", description = "", currency = CURRENCY).seal(key, GROUP))
    }

    /** The links the server lists, the second generation's to the fourth's, and whether it answers. */
    private val links = (2L..4L).mapTo(mutableListOf()) { linkAt(generation = it) }
    private var isServerReachable = true
    private var linksAsked = 0

    /** The group as this device holds it, the generations of its key it holds, and those of them the chain gave. */
    private var group: GroupEntity? = groupOf(keyGeneration = 4)
    private val held = mutableMapOf(4L to keys.getValue(4))
    private val chainGave = mutableSetOf<Long>()

    /** Keys of Mallory's own, by generation, which her links lead to. */
    private val malloryKeys = (1L..3L).associateWith { generateSymmetricEncryptionKey() }

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
                GroupKeyEntity(
                    groupUuid = groupUuid,
                    generation = generation,
                    groupKey = key,
                    isRecovered = generation in chainGave,
                )
            }
        },
        storeRecovered = { _, keys ->
            recovered += keys
            keys.filter { it.generation !in held }.forEach { key ->
                held[key.generation] = key.groupKey
                if (key.isRecovered) chainGave += key.generation
            }
        },
    )

    /** A member added at the third epoch, deriving its key alone. */
    @Test
    fun `a newcomer holding the third generation gets the first back through the chain`() = runBlocking<Unit> {
        group = groupOf(keyGeneration = 3)
        held.clear()
        held[3] = keys.getValue(3)
        links.removeAll { it.generation == 4L }

        assertContentEquals(keys.getValue(1), keyOf(generation = 1))
        assertEquals(listOf(2L, 1L), recovered.map { it.generation })
        assertContentEquals(keys.getValue(2), recovered.first().groupKey)
    }

    /** Most generations of most groups: a member who held them all from the start never asks. */
    @Test
    fun `a generation held is given without asking the server`() = runBlocking<Unit> {
        assertContentEquals(keys.getValue(4), keyOf(generation = 4))

        assertEquals(0, linksAsked)
    }

    @Test
    fun `every key got back on the way down is kept and none asked for twice`() = runBlocking<Unit> {
        keyOf(generation = 1)

        assertTrue(recovered.all { it.isRecovered })
        assertContentEquals(keys.getValue(3), keyOf(generation = 3))
        assertContentEquals(keys.getValue(2), keyOf(generation = 2))
        assertEquals(1, linksAsked)
    }

    /** Sealed under no key the chain leads to: given the one it gave, which it does not open under either. */
    @Test
    fun `a key the chain gave is given still for what opens under no key it leads to`() = runBlocking<Unit> {
        keyOf(generation = 1)

        assertContentEquals(keys.getValue(3), assertIs<Result.Success<ByteArray>>(chain.keyOf(GROUP, 3) { false }).data)
        assertEquals(2, linksAsked)
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

        assertContentEquals(keys.getValue(1), keyOf(generation = 1))
        assertEquals(listOf(1L), recovered.map { it.generation })
    }

    /** Holding the first two and the fourth: the third is the fourth's link away, and the spoiled link below it is not read. */
    @Test
    fun `the chain is walked down no further than the generation asked for`() = runBlocking<Unit> {
        held[1] = keys.getValue(1)
        held[2] = keys.getValue(2)
        listAt(generation = 3, link = linkAt(generation = 2).copy(generation = 3))

        assertContentEquals(keys.getValue(3), keyOf(generation = 3))
        assertEquals(listOf(3L), recovered.map { it.generation })
    }

    /**
     * Mallory, a member at the fourth epoch, published a link there leading to a key of her own:
     * listed first, it is tried first, and leads nowhere, no link of the third generation opening
     * under it. Alice's, the next candidate, does.
     */
    @Test
    fun `a candidate leading to a key nothing opens under is passed over for the next`() = runBlocking<Unit> {
        links.add(0, bogusLinkAt(generation = 4))

        assertContentEquals(keys.getValue(2), keyOf(generation = 2))
        assertEquals(listOf(3L, 2L), recovered.map { it.generation })
        assertContentEquals(keys.getValue(3), recovered.first().groupKey)
    }

    /** No link is sealed under the first generation's key: what the caller opens tells it from Mallory's. */
    @Test
    fun `a candidate for the first generation counts once what the caller opens opens under it`() =
        runBlocking<Unit> {
            group = groupOf(keyGeneration = 2)
            held.clear()
            held[2] = keys.getValue(2)
            links.removeAll { it.generation > 2 }
            links.add(0, bogusLinkAt(generation = 2))

            assertContentEquals(keys.getValue(1), keyOf(generation = 1))
            assertEquals(listOf(1L), recovered.map { it.generation })
        }

    /**
     * Mallory published a link at every generation, each leading to the key of hers that her link
     * below opens under, down to the first, where nothing the members sealed opens: the walk goes
     * back up, and down Alice's links instead.
     */
    @Test
    fun `a chain of candidates leading nowhere at the bottom is walked back up`() = runBlocking<Unit> {
        listsMalloryChain()

        assertContentEquals(keys.getValue(1), keyOf(generation = 1))
        assertEquals(listOf(3L, 2L, 1L), recovered.map { it.generation })
        assertContentEquals(keys.getValue(3), recovered.first().groupKey)
    }

    /**
     * The same chain of Mallory's, asked for the second generation: her key there leads on down as
     * Alice's does, her link below opening under it, and only what the members sealed under the
     * second generation tells the two apart.
     */
    @Test
    fun `a candidate at the generation asked for that leads on down counts only once what the caller opens opens under it`() =
        runBlocking<Unit> {
            listsMalloryChain()

            assertContentEquals(keys.getValue(2), keyOf(generation = 2))
            assertEquals(listOf(3L, 2L), recovered.map { it.generation })
            assertContentEquals(keys.getValue(3), recovered.first().groupKey)
        }

    /**
     * Mallory, who held the second generation's key, published a link at the fourth leading to a key
     * of hers, and one under it leading to the second's: the walk to the second passes through hers,
     * kept as what the chain gave, under which what the members sealed at the third does not open,
     * and the chain is walked again for it, from the fourth.
     */
    @Test
    fun `a key the chain gave on the way down that what its generation holds does not open under is walked for again`() =
        runBlocking<Unit> {
            val malloryKey = malloryKeys.getValue(3)

            links.add(0, linkOf(previousKey = malloryKey, sealedUnder = keys.getValue(4), generation = 4))
            links.add(0, linkOf(previousKey = keys.getValue(2), sealedUnder = malloryKey, generation = 3))

            assertContentEquals(keys.getValue(2), keyOf(generation = 2))
            assertContentEquals(malloryKey, held[3])

            assertContentEquals(keys.getValue(3), keyOf(generation = 3))
            assertEquals(2, linksAsked)
        }

    /** Every candidate at a generation leading nowhere: nothing of the walk is kept. */
    @Test
    fun `candidates all leading nowhere fail and nothing is kept`() = runBlocking<Unit> {
        listAt(generation = 3, link = bogusLinkAt(generation = 3))

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), keyResultOf(generation = 1))
        assertTrue(recovered.isEmpty())
    }

    /** As the server moving one group's link to another would have it. */
    @Test
    fun `a link sealed for another group does not open`() = runBlocking<Unit> {
        listAt(generation = 4, link = linkAt(generation = 4, sealedFor = OTHER_GROUP))

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), keyResultOf(generation = 3))
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

        listAt(generation = 4, link = GroupKeyLinkDto(generation = 4, publisherUuid = ALICE, previousKey = misplaced))

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), keyResultOf(generation = 3))
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

    /** Whatever the server holds back, nothing is kept of a walk that did not get there, and it is asked for again later. */
    @Test
    fun `a link missing fails and nothing is kept`() = runBlocking<Unit> {
        links.removeAll { it.generation == 2L }

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), keyResultOf(generation = 1))
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun `a link opening to something that is no group key does not open`() = runBlocking<Unit> {
        val sealed = assertNotNull(
            sealKeyLink(previousKey = ByteArray(16), groupKey = keys.getValue(4), groupUuid = GROUP, generation = 4),
        )

        listAt(generation = 4, link = GroupKeyLinkDto(generation = 4, publisherUuid = ALICE, previousKey = sealed))

        assertEquals(Result.Error(GroupKeyChainError.Unreadable), keyResultOf(generation = 3))
    }

    /** The chain leads back alone: a later generation is the group's Commits' to give. */
    @Test
    fun `a generation after every one held is not asked for`() = runBlocking<Unit> {
        assertEquals(Result.Error(GroupKeyChainError.NotHeld), chain.keyOf(GROUP, 5) { true })

        assertEquals(0, linksAsked)
    }

    /** Removed, or left: the server lists the chain to current members alone. */
    @Test
    fun `a group the user is no longer in asks for nothing and gives what it holds`() = runBlocking<Unit> {
        group = groupOf(keyGeneration = 4, isLeft = true)

        assertEquals(Result.Error(GroupKeyChainError.NotMember), keyResultOf(generation = 3))
        assertContentEquals(keys.getValue(4), keyOf(generation = 4))
        assertEquals(0, linksAsked)
    }

    @Test
    fun `a group this device does not hold has no key`() = runBlocking<Unit> {
        group = null

        assertEquals(Result.Error(GroupKeyChainError.NotHeld), keyResultOf(generation = 1))
        assertEquals(0, linksAsked)
    }

    @Test
    fun `a server that cannot be reached is reported and nothing kept`() = runBlocking<Unit> {
        isServerReachable = false

        assertEquals(Result.Error(GroupKeyChainError.Network(NetworkError.NO_INTERNET)), keyResultOf(generation = 1))
        assertTrue(recovered.isEmpty())
    }

    /** The key of [generation] the chain gives, told by what is sealed under it, see [content]. */
    private suspend fun keyOf(generation: Long): ByteArray =
        assertIs<Result.Success<ByteArray>>(keyResultOf(generation = generation)).data

    private suspend fun keyResultOf(generation: Long) = chain.keyOf(GROUP, generation) { key ->
        content.getValue(generation).unseal(groupKey = key, currencyKey = key, groupUuid = GROUP) != null
    }

    /**
     * Has the server list, ahead of Alice's, a link of Mallory's at every generation past the first,
     * each leading to the key of hers that her link below opens under, down to the first.
     */
    private fun listsMalloryChain() {
        links.add(0, linkOf(previousKey = malloryKeys.getValue(3), sealedUnder = keys.getValue(4), generation = 4))
        links.add(0, linkOf(previousKey = malloryKeys.getValue(2), sealedUnder = malloryKeys.getValue(3), generation = 3))
        links.add(0, linkOf(previousKey = malloryKeys.getValue(1), sealedUnder = malloryKeys.getValue(2), generation = 2))
    }

    /** A link of Mallory's at [generation], [previousKey] sealed under [sealedUnder]. */
    private fun linkOf(previousKey: ByteArray, sealedUnder: ByteArray, generation: Long) = GroupKeyLinkDto(
        generation = generation,
        publisherUuid = MALLORY,
        previousKey = assertNotNull(
            sealKeyLink(previousKey = previousKey, groupKey = sealedUnder, groupUuid = GROUP, generation = generation),
        ),
    )

    /** Has the server list [link] in place of the one at [generation]. */
    private fun listAt(generation: Long, link: GroupKeyLinkDto) {
        links[links.indexOfFirst { it.generation == generation }] = link
    }

    /**
     * The link at [generation], the key before it sealed under its own, for the group [sealedFor],
     * as Alice's Commit stored it and the server lists it for [GROUP].
     */
    private fun linkAt(generation: Long, sealedFor: String = GROUP) = GroupKeyLinkDto(
        generation = generation,
        publisherUuid = ALICE,
        previousKey = assertNotNull(
            sealKeyLink(
                previousKey = keys.getValue(generation - 1),
                groupKey = keys.getValue(generation),
                groupUuid = sealedFor,
                generation = generation,
            ),
        ),
    )

    /**
     * A link Mallory, a member in the epoch of [generation], published there: sealed under its key,
     * which she holds, and opening to a key of her own.
     */
    private fun bogusLinkAt(generation: Long) = GroupKeyLinkDto(
        generation = generation,
        publisherUuid = MALLORY,
        previousKey = assertNotNull(
            sealKeyLink(
                previousKey = generateSymmetricEncryptionKey(),
                groupKey = keys.getValue(generation),
                groupUuid = GROUP,
                generation = generation,
            ),
        ),
    )

    /** [GROUP] as this device holds it, Bob's, at [keyGeneration]. */
    private fun groupOf(keyGeneration: Long, isLeft: Boolean = false) = GroupEntity(
        uuid = GROUP,
        title = "Ski trip",
        description = "",
        currency = CURRENCY,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = BOB,
        mlsGroupId = GROUP.encodeToByteArray(),
        groupKey = keys.getValue(keyGeneration),
        keyGeneration = keyGeneration,
        isBackedUp = true,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}

private val CURRENCY = CurrencyCatalog.defaultCurrency
