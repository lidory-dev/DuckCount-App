package group

import database.data.local.entity.GroupEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.inviteeKeyPackage
import mls.processGroupMessageIn
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.NetworkError
import util.Result
import vault.InMemoryProfileKeyVault
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * What Bob's device posts of the key to his profile into the two groups Alice created with him,
 * Carol in the first, and what the server answering each post comes to: with every MLS client made
 * through the library itself, the members reading what he posts as their group sync would, and the
 * database, the vault and the server stood in for.
 */
class GroupProfileKeyDeliveryTest {

    private val bobStore = InMemoryMlsStore()

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = bobStore)
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    /** The key to Bob's profile, as his vault and device hold it. */
    private val bobKey = generateSymmetricEncryptionKey()
    private val profileKeyVault = InMemoryProfileKeyVault(profileEncryptionKey = bobKey)

    /** Each group's MLS group on Alice's device, Carol's, and Bob's. */
    private val aliceGroups = mutableMapOf<String, ByteArray>()
    private var carolGroupId: ByteArray? = null

    /** Bob's groups, in the order the database lists them, owed his key until a test says otherwise. */
    private val groups = linkedMapOf(
        GROUP to joined(groupUuid = GROUP, others = listOf(carol)),
        OTHER_GROUP to joined(groupUuid = OTHER_GROUP, others = emptyList()),
    )

    /** What the server answers a post into each group, a `200` unless a test says otherwise. */
    private val answers = mutableMapOf<String, GroupPostAnswer>()

    /** What reached the server, in order: each post's group and message. */
    private val posts = mutableListOf<Pair<String, ByteArray>>()

    private val resends = ResendsBooked()

    private val delivery = GroupProfileKeyDelivery(
        profileKeyVault = profileKeyVault,
        // As GroupDao.getOwedProfileKey selects them, for groups held, not left, with nothing pending.
        owedGroups = { hash -> groups.values.filter { it.handedProfileKeyHash != hash } },
        setHanded = { groupUuid, hash -> groups[groupUuid] = groups.getValue(groupUuid).holding(hash) },
        client = { Result.Success(bob) },
        post = { groupUuid, message ->
            posts += groupUuid to message
            answers[groupUuid] ?: GroupPostAnswer.Posted
        },
        resendScheduler = resends,
    )

    @Test
    fun `every group owed the user's key is posted it and then holds it`() = runBlocking<Unit> {
        assertEquals(Result.Success(Unit), delivery.handOut(behind = emptySet()))

        assertEquals(listOf(GROUP, OTHER_GROUP), posts.map { it.first })
        assertTrue(groups.values.all { it.handedProfileKeyHash == profileEncryptionKeyPartialHash(bobKey) })
        assertTrue(resends.seconds.isEmpty())
    }

    /** As their group sync reads it, Alice the one member whose Commits it takes. */
    @Test
    fun `every other member reads the key as Bob's own`() = runBlocking<Unit> {
        delivery.handOut(behind = emptySet())

        val post = posts.first { it.first == GROUP }.second

        val readers = listOf(alice to aliceGroups.getValue(GROUP), carol to assertNotNull(carolGroupId))

        for ((client, mlsGroupId) in readers) {
            val read = assertIs<ProcessedMessage.ApplicationMessage>(
                client.processGroupMessageIn(mlsGroupId = mlsGroupId, message = post, creatorUuid = ALICE).processed,
            )

            assertEquals(BOB, read.senderUserUuid)
            assertContentEquals(bobKey, ownProfileKeyOf(read.payload))
        }
    }

    /** Nearly every sync: the hash the device keeps tells it without reading the vault. */
    @Test
    fun `groups holding the current key are posted nothing and the vault is not read`() = runBlocking<Unit> {
        holdAll(profileEncryptionKeyPartialHash(bobKey))

        assertEquals(Result.Success(Unit), delivery.handOut(behind = emptySet()))

        assertTrue(posts.isEmpty())
        assertEquals(0, profileKeyVault.reads)
    }

    @Test
    fun `the key is read from the vault once when the device keeps no hash of it`() = runBlocking<Unit> {
        profileKeyVault.cachedPartialHash = null

        assertEquals(Result.Success(Unit), delivery.handOut(behind = emptySet()))

        assertEquals(1, profileKeyVault.reads)
        assertEquals(2, posts.size)
    }

    /** Removing a contact rotates the key, see `ProfileRepository.rotateProfileKey`. */
    @Test
    fun `a rotated key leaves every group owed it and each is posted the new one`() = runBlocking<Unit> {
        holdAll(profileEncryptionKeyPartialHash(bobKey))

        val newKey = generateSymmetricEncryptionKey()

        profileKeyVault.replaceProfileEncryptionKey(profileEncryptionKey = newKey)

        assertEquals(Result.Success(Unit), delivery.handOut(behind = emptySet()))

        assertEquals(listOf(GROUP, OTHER_GROUP), posts.map { it.first })
        assertTrue(groups.values.all { it.handedProfileKeyHash == profileEncryptionKeyPartialHash(newKey) })

        val read = assertIs<ProcessedMessage.ApplicationMessage>(
            alice.processGroupMessageIn(
                mlsGroupId = aliceGroups.getValue(OTHER_GROUP),
                message = posts.last().second,
                creatorUuid = ALICE,
            ).processed,
        )

        assertContentEquals(newKey, ownProfileKeyOf(read.payload))
    }

    /** Nobody else to read it, nobody holding an older key for Bob either. */
    @Test
    fun `a 409 counts as the group holding the key`() = runBlocking<Unit> {
        answers[OTHER_GROUP] = GroupPostAnswer.NoOtherMembers

        assertEquals(Result.Success(Unit), delivery.handOut(behind = emptySet()))

        assertEquals(profileEncryptionKeyPartialHash(bobKey), groups.getValue(OTHER_GROUP).handedProfileKeyHash)
    }

    /** Alice removed Bob, which the next drain reads, or the proxy answered for a server being redeployed. */
    @Test
    fun `a 404 leaves the group owed unreported and the next group is posted all the same`() = runBlocking<Unit> {
        answers[GROUP] = GroupPostAnswer.NotMember

        assertEquals(Result.Success(Unit), delivery.handOut(behind = emptySet()))

        assertEquals(listOf(GROUP, OTHER_GROUP), posts.map { it.first })
        assertNull(groups.getValue(GROUP).handedProfileKeyHash)
        assertEquals(profileEncryptionKeyPartialHash(bobKey), groups.getValue(OTHER_GROUP).handedProfileKeyHash)
    }

    /** The allowance is for all of Bob's groups together: the next post would be refused too. */
    @Test
    fun `a 429 books the pass again after its Retry-After and stops it there`() = runBlocking<Unit> {
        answers[GROUP] = GroupPostAnswer.RateLimited(retryAfterSeconds = 42)

        assertEquals(Result.Success(Unit), delivery.handOut(behind = emptySet()))

        assertEquals(listOf(GROUP), posts.map { it.first })
        assertEquals(listOf(42L), resends.seconds)
        assertTrue(groups.values.all { it.handedProfileKeyHash == null })
    }

    @Test
    fun `a post the server does not answer is reported and the pass stops there`() = runBlocking<Unit> {
        answers[GROUP] = GroupPostAnswer.Failed(error = NetworkError.NO_INTERNET)

        assertEquals(
            Result.Error(GroupSyncError.Network(NetworkError.NO_INTERNET)),
            delivery.handOut(behind = emptySet()),
        )

        assertEquals(listOf(GROUP), posts.map { it.first })
        assertTrue(groups.values.all { it.handedProfileKeyHash == null })
        assertTrue(resends.seconds.isEmpty())
    }

    /** Its members are past the epoch Bob is in: those seated since could not read the post. */
    @Test
    fun `a group the sync could not catch up with is not posted into`() = runBlocking<Unit> {
        assertEquals(Result.Success(Unit), delivery.handOut(behind = setOf(GROUP)))

        assertEquals(listOf(OTHER_GROUP), posts.map { it.first })
        assertNull(groups.getValue(GROUP).handedProfileKeyHash)
    }

    /** Storage failing says nothing about the groups, which the next sync posts into. */
    @Test
    fun `a post whose encryption cannot be stored is reported and the group stays owed`() = runBlocking<Unit> {
        bobStore.failing = setOf(MlsNamespace.GROUP_STATE)

        assertEquals(Result.Error(GroupSyncError.Storage), delivery.handOut(behind = emptySet()))

        assertTrue(posts.isEmpty())
        assertTrue(groups.values.all { it.handedProfileKeyHash == null })
    }

    /** Has every group hold the key whose hash is [hash]. */
    private fun holdAll(hash: String) {
        groups.keys.toList().forEach { groups[it] = groups.getValue(it).holding(hash) }
    }

    /**
     * Has Alice create [groupUuid] with Bob and [others], and Bob join it from its Welcome, and
     * returns his row of it, owed his key.
     */
    private fun joined(groupUuid: String, others: List<MlsClient>): GroupEntity {
        val groupKey = generateSymmetricEncryptionKey()
        val creation = alice.createGroup(
            groupUuid = groupUuid,
            invitees = (listOf(bob) + others).map { it.inviteeKeyPackage() },
            welcomePayload = welcomePayloadOf(groupKey = groupKey, profileKeys = emptyMap()),
        )

        aliceGroups[groupUuid] = creation.mlsGroupId

        others.forEach { other ->
            carolGroupId = other.joinGroup(
                welcome = creation.welcome,
                expectedGroupUuid = groupUuid,
                expectedMembers = listOf(ALICE, BOB),
                pinMembers = false,
            )
        }

        val bobGroupId = bob.joinGroup(
            welcome = creation.welcome,
            expectedGroupUuid = groupUuid,
            expectedMembers = listOf(ALICE) + others.map { it.userUuid() },
            pinMembers = false,
        )

        return GroupEntity(
            uuid = groupUuid,
            title = "Ski trip",
            description = "Chalet and lift passes",
            currency = CurrencyCatalog.defaultCurrency,
            ownerUuid = ALICE,
            mlsGroupId = bobGroupId,
            groupKey = groupKey,
            isBackedUp = true,
            isLeft = false,
            handedProfileKeyHash = null,
        )
    }
}

/** [this] once recorded as holding the user's key whose hash is [handedProfileKeyHash]. */
private fun GroupEntity.holding(handedProfileKeyHash: String?) = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
