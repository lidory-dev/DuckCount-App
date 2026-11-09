package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.remote.EncryptedFieldDto
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.commitEpochOf
import mls.holdsGroup
import mls.inviteeKeyPackage
import mls.rejoinGroupInfoIn
import mls.rejoinGroupOf
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsNamespace
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.MemberProfileBook
import vault.VaultError
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** When Dave left the group, as the server lists his departure. */
private const val DAVE_LEFT_AT = "2026-10-30T12:00:00.000Z"

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What the group sync makes of a group a log-out took the MLS state of, see [GroupRejoiner]: Alice
 * created it with Bob, Carol and Dave, and whoever logs out and back in, Alice or Bob, asks the
 * others for a GroupInfo, rejoins from the first one signed by somebody their device pinned, and
 * sends the Commit doing so, which every other member's device takes. Every device runs the group
 * sync, the changer, the sender and the rejoiner themselves, with every MLS client made through the
 * library, the database stood in for on each, and the server by [Server], which holds to one Commit
 * per epoch, and to the rejoin routes, as the API does. Alice's and Bob's contacts are each other,
 * and Carol is Alice's, but pinned on no device that logs back in.
 */
class GroupSyncRejoinTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

    private val creation = alice.createGroup(
        groupUuid = GROUP,
        invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage(), dave.inviteeKeyPackage()),
        welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
    )

    /** The key of the epoch the creation leaves the group at, which its information is sealed under. */
    private val firstKey = alice.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)

    private val version = FIELDS.sealedAs(creator = alice, groupKey = firstKey, groupUuid = GROUP)

    private val server = Server()

    /** The time on every device, in milliseconds since the epoch: just after Dave left, unless a test moves it. */
    private var now = millisOf(DAVE_LEFT_AT) + 1_000

    private val aliceDevice = Device(client = alice, userUuid = ALICE, mlsGroupId = creation.mlsGroupId, contacts = setOf(BOB, CAROL))
    private val bobDevice = Device(client = bob, userUuid = BOB, mlsGroupId = joined(bob), contacts = setOf(ALICE))
    private val carolDevice = Device(client = carol, userUuid = CAROL, mlsGroupId = joined(carol), contacts = setOf(ALICE))

    init {
        joined(dave)
    }

    /**
     * Alice logs out and back in: her device asks for a GroupInfo, Carol's and Bob's publish theirs,
     * and hers rejoins from Bob's, Carol being nobody it pinned, with the link back to the key of the
     * epoch it was built in, which her copy holds.
     */
    @Test
    fun `a restored owner asks for a GroupInfo and rejoins from a pinned member's past an untrusted one`() =
        runBlocking<Unit> {
            val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

            assertNull(aliceBack.sync().failure)

            assertEquals(listOf(ALICE), server.requested)
            assertNull(aliceBack.group().mlsGroupId)
            assertTrue(server.rejoins.isEmpty())

            // Carol's alone, from nobody Alice's device pinned: nothing to rejoin from, and asked again.
            assertNull(carolDevice.sync().failure)
            assertNull(aliceBack.sync().failure)

            assertTrue(server.rejoins.isEmpty())
            assertEquals(listOf(ALICE, ALICE), server.requested)
            assertNull(aliceBack.group().mlsGroupId)
            assertFalse(aliceBack.client.holdsGroup(mlsGroupId = creation.mlsGroupId))

            // Carol's still listed first.
            assertNull(bobDevice.sync().failure)
            assertEquals(listOf(CAROL, BOB), server.groupInfos.keys.toList())

            assertNull(aliceBack.sync().failure)

            val group = aliceBack.group()
            val rejoin = server.rejoins.single().second

            assertContentEquals(creation.mlsGroupId, group.mlsGroupId)
            assertEquals(2L, group.keyGeneration)
            assertTrue(group.isBackedUp)
            assertTrue(aliceBack.pending.isEmpty())
            assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE), aliceBack.memberRows())
            assertEquals(setOf(ALICE, BOB, CAROL, DAVE), aliceBack.client.rosterOf(mlsGroupId = creation.mlsGroupId).memberUuids.toSet())
            assertEquals(2L, server.generation)
            assertTrue(server.requests.isEmpty())
            assertContentEquals(
                firstKey,
                rejoin.previousKey?.openKeyLink(groupKey = group.groupKey, groupUuid = GROUP, generation = 2),
            )
            assertContentEquals(group.groupKey, aliceBack.keyCopies.keyOf(GROUP))
            assertEquals(2L, aliceBack.keyCopies.generationOf(GROUP))
            assertTrue(aliceBack.handedOutBehind.last().isEmpty())
        }

    /**
     * Carol is nobody Alice's device pinned, no contact left of hers: what vouches for her is the key
     * a device of Alice's recorded from the group's tree while it held the group.
     */
    @Test
    fun `a restored owner rejoins from a member the vault holds the key of whether a contact or not`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = emptyList(), contacts = setOf(BOB, CAROL))
        aliceBack.vaultBook = vouching(GROUP, CAROL, carol.signaturePublicKey())

        assertNull(aliceBack.sync().failure)
        assertNull(carolDevice.sync().failure)
        assertNull(aliceBack.sync().failure)

        assertContentEquals(creation.mlsGroupId, aliceBack.group().mlsGroupId)
        assertEquals(2L, aliceBack.group().keyGeneration)
        assertEquals(1, server.rejoins.size)

        // What the vault vouches for may be out of date: no link for a signer it alone answers for.
        assertNull(server.rejoins.single().second.previousKey)

        // The group in step: the member who vouched takes the rejoin as any other.
        assertNull(carolDevice.sync().failure)
        assertContentEquals(aliceBack.group().groupKey, carolDevice.group().groupKey)
    }

    /** Nothing records them for a group the user created until its first Commit, and the vault needs them. */
    @Test
    fun `a group created here records its members' keys from its tree for the vault`() = runBlocking<Unit> {
        assertTrue(aliceDevice.signatureKeys.isEmpty())

        assertNull(aliceDevice.sync().failure)

        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), aliceDevice.signatureKeys.keys)
        assertContentEquals(carol.signaturePublicKey(), aliceDevice.signatureKeys[CAROL])
    }

    /** The tree of a rejoin the server has not taken is one it may still refuse. */
    @Test
    fun `a rejoin not settled records no keys from its tree`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        assertNull(aliceBack.sync().failure)
        assertNull(bobDevice.sync().failure)

        aliceBack.diesBeforeSend = true
        aliceBack.sync()

        assertTrue(aliceBack.pending.isNotEmpty())
        assertTrue(aliceBack.signatureKeys.isEmpty())
    }

    /** A tree is its creator's word for that group alone. */
    @Test
    fun `what the vault holds of another group vouches for nobody in this one`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = emptyList(), contacts = setOf(BOB, CAROL))
        aliceBack.vaultBook = vouching(OTHER_GROUP, CAROL, carol.signaturePublicKey())

        assertNull(aliceBack.sync().failure)
        assertNull(carolDevice.sync().failure)
        assertNull(aliceBack.sync().failure)

        assertNull(aliceBack.group().mlsGroupId)
        assertTrue(server.rejoins.isEmpty())
    }

    @Test
    fun `a member signing under another key than the vault holds is not taken`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = emptyList(), contacts = setOf(BOB, CAROL))
        aliceBack.vaultBook = vouching(GROUP, CAROL, bob.signaturePublicKey())

        assertNull(aliceBack.sync().failure)
        assertNull(carolDevice.sync().failure)
        assertNull(aliceBack.sync().failure)

        assertNull(aliceBack.group().mlsGroupId)
        assertTrue(server.rejoins.isEmpty())
    }

    /** The vault is read as the key ring is, and failing, stops the pass the same way. */
    @Test
    fun `a vault out of reach leaves the rejoin for the next sync`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        assertNull(aliceBack.sync().failure)
        assertNull(bobDevice.sync().failure)

        aliceBack.vaultBook = null

        assertNotNull(aliceBack.sync().failure)
        assertNull(aliceBack.group().mlsGroupId)
        assertTrue(server.rejoins.isEmpty())

        aliceBack.vaultBook = MemberProfileBook.EMPTY

        assertNull(aliceBack.sync().failure)
        assertContentEquals(creation.mlsGroupId, aliceBack.group().mlsGroupId)
    }

    private fun vouching(groupUuid: String, userUuid: String, signatureKey: ByteArray): MemberProfileBook =
        MemberProfileBook.EMPTY.withMemberKeys(changed = mapOf(groupUuid to mapOf(userUuid to signatureKey)), forgotten = emptySet())

    /** In step again: every member's device takes the rejoin, and Alice's makes the next Commit. */
    @Test
    fun `the members take the rejoin and the owner changes the members from there`() = runBlocking<Unit> {
        val aliceBack = aliceRejoins()

        assertNull(bobDevice.sync().failure)
        assertNull(carolDevice.sync().failure)

        assertEquals(2L, bobDevice.group().keyGeneration)
        assertContentEquals(aliceBack.group().groupKey, bobDevice.group().groupKey)
        assertContentEquals(aliceBack.group().groupKey, carolDevice.group().groupKey)
        assertContentEquals(alice.signaturePublicKey(), bobDevice.signatureKeys[ALICE])
        assertFalse(bobDevice.group().isAwaitingOwner)

        val removal = aliceBack.remove(CAROL)

        assertEquals(MembershipChangeOutcome.MADE, assertIs<Result.Success<MembershipChangeOutcome>>(removal).data)

        assertNull(bobDevice.sync().failure)

        assertEquals(3L, bobDevice.group().keyGeneration)
        assertContentEquals(aliceBack.group().groupKey, bobDevice.group().groupKey)
        assertEquals(rowsOf(ALICE, BOB, DAVE), bobDevice.memberRows())
    }

    /** Any member rejoins, not only the owner: Bob, from Alice's GroupInfo, the one his device pinned. */
    @Test
    fun `a restored member rejoins from the owner's GroupInfo`() = runBlocking<Unit> {
        val bobBack = loggedBackIn(bob, pinned = listOf(alice), contacts = setOf(ALICE))

        assertNull(bobBack.sync().failure)
        assertEquals(listOf(BOB), server.requested)

        assertNull(aliceDevice.sync().failure)
        assertEquals(listOf(ALICE to FIRST_KEY_GENERATION), server.published)

        assertNull(bobBack.sync().failure)

        val group = bobBack.group()

        assertContentEquals(creation.mlsGroupId, group.mlsGroupId)
        assertEquals(2L, group.keyGeneration)
        assertTrue(bobBack.pending.isEmpty())

        assertNull(aliceDevice.sync().failure)
        assertNull(carolDevice.sync().failure)

        assertContentEquals(group.groupKey, aliceDevice.group().groupKey)
        assertContentEquals(group.groupKey, carolDevice.group().groupKey)
        assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE), aliceDevice.memberRows())
    }

    /**
     * Another member's Commit got there first: the state the rejoin was built in is deleted and the
     * rejoin dropped, and the next pass rejoins anew, with a nonce of its own.
     */
    @Test
    fun `a rejoin refused for its epoch is forgotten and the next pass rejoins anew`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()

        server.answers += GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = 2)

        assertNull(aliceBack.sync().failure)

        assertNull(aliceBack.group().mlsGroupId)
        assertTrue(aliceBack.pending.isEmpty())
        assertFalse(aliceBack.client.holdsGroup(mlsGroupId = creation.mlsGroupId))
        assertEquals(FIRST_KEY_GENERATION, server.generation)

        assertNull(aliceBack.sync().failure)

        val (first, second) = server.rejoins.map { it.second }

        assertNotEquals(first.nonce, second.nonce)
        assertContentEquals(creation.mlsGroupId, aliceBack.group().mlsGroupId)
        assertEquals(2L, aliceBack.group().keyGeneration)
        assertTrue(aliceBack.pending.isEmpty())
    }

    /**
     * The server took the rejoin and the answer was lost: the rejoin waits, the group left alone,
     * and the next pass sends it again, which the server answers `409` for its nonce.
     */
    @Test
    fun `a rejoin whose answer was lost is sent again and taken as made`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()

        server.losesAnswer = true

        assertNull(aliceBack.sync().failure)

        assertEquals(MembershipChangeKind.REJOIN, aliceBack.pending[GROUP]?.kind)
        assertContentEquals(creation.mlsGroupId, aliceBack.group().mlsGroupId)
        assertEquals(FIRST_KEY_GENERATION, aliceBack.group().keyGeneration)
        assertTrue(GROUP in aliceBack.handedOutBehind.last())
        assertEquals(2L, server.generation)

        assertNull(aliceBack.sync().failure)

        val (first, second) = server.rejoins.map { it.second }

        assertEquals(first, second)
        assertEquals(2L, server.generation)
        assertEquals(2L, aliceBack.group().keyGeneration)
        assertTrue(aliceBack.group().isBackedUp)
        assertTrue(aliceBack.pending.isEmpty())

        assertNull(bobDevice.sync().failure)

        assertContentEquals(aliceBack.group().groupKey, bobDevice.group().groupKey)
    }

    /** As though the process went away once the rejoin was saved: its resend was booked first. */
    @Test
    fun `a rejoin saved and never sent goes out with the next pass`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()

        aliceBack.diesBeforeSend = true

        aliceBack.sync()

        assertTrue(server.rejoins.isEmpty())
        assertEquals(MembershipChangeKind.REJOIN, aliceBack.pending[GROUP]?.kind)
        assertTrue(aliceBack.resends.seconds.isNotEmpty())

        assertNull(aliceBack.sync().failure)

        assertEquals(1, server.rejoins.size)
        assertEquals(2L, aliceBack.group().keyGeneration)
        assertTrue(aliceBack.pending.isEmpty())
    }

    /** Nothing names a state that was never saved, which is deleted again at once. */
    @Test
    fun `a rejoin that cannot be saved leaves no state behind`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()

        aliceBack.failsSave = true

        assertEquals(GroupSyncError.Storage, aliceBack.sync().failure)

        assertNull(aliceBack.group().mlsGroupId)
        assertTrue(aliceBack.pending.isEmpty())
        assertFalse(aliceBack.client.holdsGroup(mlsGroupId = creation.mlsGroupId))
        assertTrue(server.rejoins.isEmpty())
    }

    /**
     * The database fails as the rejoin is saved, and the library's storage, the very same database,
     * with it, so that the state the rejoin stored is not deleted again either: the next pass deletes
     * it before anything else, there being nothing left to rejoin from by then, whose rejoin would
     * have deleted it first. Held, it would refuse every Welcome to the group, see [GroupRejoiner].
     */
    @Test
    fun `a rejoin state the failing storage kept is deleted by the next pass`() = runBlocking<Unit> {
        val store = InMemoryMlsStore()
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL), store = store)

        aliceBack.sync()
        bobDevice.sync()

        aliceBack.failsSave = true
        aliceBack.failsStorageWithSave = true

        assertEquals(GroupSyncError.Storage, aliceBack.sync().failure)

        aliceBack.failsSave = false
        store.failing = emptySet()

        assertTrue(aliceBack.client.holdsGroup(mlsGroupId = creation.mlsGroupId))

        server.groupInfos.clear()

        assertNull(aliceBack.sync().failure)

        assertFalse(aliceBack.client.holdsGroup(mlsGroupId = creation.mlsGroupId))
        assertNull(aliceBack.group().mlsGroupId)
        assertTrue(aliceBack.pending.isEmpty())
        assertTrue(server.rejoins.isEmpty())
    }

    /** Nobody asked: Bob's device publishes nothing until Alice's does, and once only. */
    @Test
    fun `members publish a GroupInfo only once another asks for one`() = runBlocking<Unit> {
        assertNull(bobDevice.sync().failure)
        assertTrue(server.published.isEmpty())

        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()

        assertNull(bobDevice.sync().failure)
        assertNull(bobDevice.sync().failure)

        assertEquals(listOf(BOB to FIRST_KEY_GENERATION), server.published)
    }

    /** A GroupInfo of an epoch the group left would be of use to nobody. */
    @Test
    fun `a member that could not catch up publishes no GroupInfo`() = runBlocking<Unit> {
        loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL)).sync()

        bobDevice.areMessagesReachable = false

        assertEquals(GroupSyncError.Network(NetworkError.NO_INTERNET), bobDevice.sync().failure)
        assertTrue(server.published.isEmpty())
    }

    /**
     * Bob's device removed Dave while Alice was logged out, so her copy is of the epoch before the
     * one she rejoins from: her rejoin carries no link back, and the members' devices publish theirs,
     * Bob's first, which her device then reads the epoch it skipped through.
     */
    @Test
    fun `the members publish the link back a rejoin could not carry`() = runBlocking<Unit> {
        server.departs(DAVE)
        now = millisOf(DAVE_LEFT_AT) + DEPARTED_GRACE.inWholeMilliseconds + 1_000

        assertNull(bobDevice.sync().failure)
        assertNull(carolDevice.sync().failure)
        assertEquals(2L, server.generation)

        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()
        assertNull(aliceBack.sync().failure)

        assertNull(server.rejoins.single().second.previousKey)
        assertEquals(3L, aliceBack.group().keyGeneration)
        assertEquals(rowsOf(ALICE, BOB, CAROL), aliceBack.memberRows())

        assertNull(bobDevice.sync().failure)
        assertNull(carolDevice.sync().failure)

        assertEquals(listOf(BOB), server.links.filter { it.generation == 3L }.map { it.publisherUuid })

        val skipped = bobDevice.keys.getValue(2L).groupKey

        val chain = GroupKeyChain(
            keyLinks = { server.links(userUuid = ALICE) },
            group = { aliceBack.groups[it] },
            heldKeys = { aliceBack.keys.values.toList() },
            storeRecovered = { _, _ -> },
        )

        val key = chain.keyOf(groupUuid = GROUP, generation = 2) { it contentEquals skipped }

        assertContentEquals(skipped, assertIs<Result.Success<ByteArray>>(key).data)
    }

    /**
     * Every new route answers `404` until the server takes rejoins, which is no failure: the
     * restored device waits, and a member's device asked publishes nothing.
     */
    @Test
    fun `a server taking no rejoins yet fails nothing on either side`() = runBlocking<Unit> {
        server.isShut = true

        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        assertNull(aliceBack.sync().failure)
        assertTrue(server.requested.isEmpty())
        assertNull(aliceBack.group().mlsGroupId)

        // A request made before it shut them.
        server.requests += ALICE

        assertNull(bobDevice.sync().failure)
        assertTrue(server.published.isEmpty())
    }

    /**
     * The rejoin's answer was lost, and the server then shut its rejoins, its minimum build lowered
     * again: sent again, the rejoin is answered `404`, which fails nothing and books no resend, and the
     * first sync once the server takes rejoins again sends it as before.
     */
    @Test
    fun `a rejoin answered 404 as it is sent again fails nothing and books no resend`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()

        server.answers += GroupMembershipAnswer.Failed(error = NetworkError.NO_INTERNET)

        aliceBack.sync()

        assertEquals(MembershipChangeKind.REJOIN, aliceBack.pending[GROUP]?.kind)

        server.isShut = true

        val booked = aliceBack.resends.seconds.toList()

        assertNull(aliceBack.sync().failure)

        assertEquals(booked, aliceBack.resends.seconds)
        assertEquals(MembershipChangeKind.REJOIN, aliceBack.pending[GROUP]?.kind)
        assertEquals(FIRST_KEY_GENERATION, server.generation)

        server.isShut = false

        assertNull(aliceBack.sync().failure)

        assertEquals(2L, server.generation)
        assertEquals(2L, aliceBack.group().keyGeneration)
        assertTrue(aliceBack.pending.isEmpty())
    }

    /**
     * Dave left as Alice was logged out, and nobody removed him yet, Bob's device waiting out the
     * grace: the tree of Bob's GroupInfo still holds him, as the members Alice's device expects it to
     * do. Rejoined, the group is held by the rest of that very pass, whose removal of the departed
     * her device makes at once, as its creator's.
     */
    @Test
    fun `a rejoin from a tree still holding a departed member is taken and the same pass removes them`() =
        runBlocking<Unit> {
            server.departs(DAVE)

            val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

            aliceBack.sync()

            // Not Bob's to remove yet.
            assertNull(bobDevice.sync().failure)
            assertEquals(FIRST_KEY_GENERATION, server.generation)
            assertEquals(listOf(BOB to FIRST_KEY_GENERATION), server.published)

            assertNull(aliceBack.sync().failure)

            assertEquals(ALICE, server.rejoins.single().first)
            assertContentEquals(creation.mlsGroupId, aliceBack.group().mlsGroupId)
            assertEquals(listOf(ALICE), server.requested)
            assertEquals(3L, server.generation)
            assertTrue(server.departed.isEmpty())
            assertEquals(3L, aliceBack.group().keyGeneration)
            assertEquals(rowsOf(ALICE, BOB, CAROL), aliceBack.memberRows())

            assertNull(bobDevice.sync().failure)

            assertEquals(3L, bobDevice.group().keyGeneration)
            assertContentEquals(aliceBack.group().groupKey, bobDevice.group().groupKey)
            assertEquals(rowsOf(ALICE, BOB, CAROL), bobDevice.memberRows())
        }

    /**
     * Everybody but Alice left the group, and nobody's device can publish a GroupInfo of it for her:
     * hers neither asks for one nor looks for one, pass after pass.
     */
    @Test
    fun `a group brought back with nobody else in it asks nobody for a GroupInfo`() = runBlocking<Unit> {
        server.departs(BOB)
        server.departs(CAROL)
        server.departs(DAVE)

        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        assertNull(aliceBack.sync().failure)
        assertNull(aliceBack.sync().failure)

        assertTrue(server.requested.isEmpty())
        assertTrue(server.groupInfosListedBy.isEmpty())
        assertNull(aliceBack.group().mlsGroupId)
    }

    /**
     * The server lists Mallory, whom no tree holds: the GroupInfo is not one of the group as the
     * server lists it, which another of the same epoch would not be either, so the request is made
     * again and nothing is built.
     */
    @Test
    fun `a GroupInfo whose tree is not the group the server lists is not rejoined from`() = runBlocking<Unit> {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()

        server.members += MALLORY

        assertNull(aliceBack.sync().failure)

        assertTrue(server.rejoins.isEmpty())
        assertEquals(listOf(ALICE, ALICE), server.requested)
        assertNull(aliceBack.group().mlsGroupId)
        assertFalse(aliceBack.client.holdsGroup(mlsGroupId = creation.mlsGroupId))
    }

    /**
     * Alice removed Bob while his rejoin waited on the server: it is forgotten with its state, and
     * the group, no longer listed, left.
     */
    @Test
    fun `a rejoin pending in a group the server no longer lists is forgotten and the group left`() =
        runBlocking<Unit> {
            val bobBack = loggedBackIn(bob, pinned = listOf(alice), contacts = setOf(ALICE))

            bobBack.sync()
            aliceDevice.sync()

            server.answers += GroupMembershipAnswer.Failed(error = NetworkError.NO_INTERNET)

            bobBack.sync()

            assertEquals(MembershipChangeKind.REJOIN, bobBack.pending[GROUP]?.kind)

            assertIs<Result.Success<MembershipChangeOutcome>>(aliceDevice.remove(BOB))

            val pass = bobBack.sync()

            assertEquals(
                listOf(GroupRemoval(groupUuid = GROUP, title = FIELDS.title, removerUuid = ALICE, removerFullName = null)),
                pass.removed,
            )
            assertTrue(bobBack.group().isLeft)
            assertNull(bobBack.group().mlsGroupId)
            assertTrue(bobBack.pending.isEmpty())
            assertFalse(bobBack.client.holdsGroup(mlsGroupId = creation.mlsGroupId))
        }

    /** A rejoin refused on a member's device is a Commit refused at its epoch, which its creator replaces. */
    @Test
    fun `a rejoin reads as a Commit built in the epoch of its GroupInfo`() = runBlocking<Unit> {
        val exported = bob.rejoinGroupInfoIn(mlsGroupId = checkNotNull(bobDevice.group().mlsGroupId))
        val aliceBack = restoredClientOf(alice, pinned = listOf(bob))

        val rejoin = aliceBack.rejoinGroupOf(
            groupInfo = exported.groupInfo,
            groupUuid = GROUP,
            expectedEpoch = FIRST_KEY_GENERATION,
            expectedMembers = listOf(BOB, CAROL, DAVE),
            vouchedMembers = emptyMap(),
        )

        assertEquals(FIRST_KEY_GENERATION, exported.epoch)
        assertEquals(2uL, rejoin.epoch)
        assertEquals(FIRST_KEY_GENERATION, commitEpochOf(rejoin.commit))
        assertNull(commitEpochOf(exported.groupInfo))
    }

    /** Has Alice log out and back in, and her device rejoin from Bob's GroupInfo, and returns it. */
    private suspend fun aliceRejoins(): Device {
        val aliceBack = loggedBackIn(alice, pinned = listOf(bob), contacts = setOf(BOB, CAROL))

        aliceBack.sync()
        bobDevice.sync()
        assertNull(aliceBack.sync().failure)
        assertNotNull(aliceBack.group().mlsGroupId)

        return aliceBack
    }

    /**
     * Has the owner of [client] log out and back in, and returns their device as the group sync
     * brought the group back from their copy of its key, see [GroupSync]: no MLS state, the members
     * the server lists, with the identity the vault kept and [pinned] pinned, as their contacts are,
     * its library storing into [store].
     */
    private fun loggedBackIn(
        client: MlsClient,
        pinned: List<MlsClient>,
        contacts: Set<String>,
        store: InMemoryMlsStore = InMemoryMlsStore(),
    ): Device {
        val userUuid = client.userUuid()
        val copies = server.keyCopiesOf(userUuid)

        return Device(
            client = restoredClientOf(client, pinned = pinned, store = store),
            userUuid = userUuid,
            mlsGroupId = null,
            contacts = contacts,
            groupKey = checkNotNull(copies.keyOf(GROUP)),
            keyGeneration = copies.generationOf(GROUP),
            store = store,
        )
    }

    /** [client]'s identity as the vault brings it back after a log-out, into [store], empty, [pinned] pinned. */
    private fun restoredClientOf(
        client: MlsClient,
        pinned: List<MlsClient>,
        store: InMemoryMlsStore = InMemoryMlsStore(),
    ): MlsClient {
        val restored = MlsClient.restore(
            userUuid = client.userUuid(),
            secret = ByteArray(32) { 9 },
            store = store,
            signaturePrivateKey = client.signaturePrivateKey(),
            signaturePublicKey = client.signaturePublicKey(),
        )

        pinned.forEach { restored.pinIdentity(userUuid = it.userUuid(), signatureKey = it.signaturePublicKey()) }

        return restored
    }

    /** Has [client] join Alice's group from its Welcome, beside everybody else, and returns their copy of it. */
    private fun joined(client: MlsClient): ByteArray = client.joinGroup(
        welcome = creation.welcome,
        expectedGroupUuid = GROUP,
        expectedMembers = listOf(ALICE, BOB, CAROL, DAVE) - client.userUuid(),
        pinMembers = false,
    )

    /**
     * The server as the API holds a group: its current members, those departed, its epoch, the
     * messages it stores for each member, every Commit for the epoch it is at alone, see
     * [GroupMembershipAnswer.KeyGenerationNotCurrent], the links back through its keys and each
     * member's copy of its key, the requests for a GroupInfo and the GroupInfos of the epoch it is in.
     */
    private inner class Server {
        val members = mutableListOf(ALICE, BOB, CAROL, DAVE)
        val departed = mutableListOf<DepartedMemberDto>()
        var generation = FIRST_KEY_GENERATION

        /** Whether the server takes no rejoin yet, answering every route of theirs `404`. */
        var isShut = false

        /** Who asks for a GroupInfo now, and every request taken, in order. */
        val requests = mutableSetOf<String>()
        val requested = mutableListOf<String>()

        /**
         * The GroupInfos of the epoch the group is in, by who published them, oldest first, every one
         * taken, and who listed them, in order.
         */
        val groupInfos = linkedMapOf<String, ByteArray>()
        val published = mutableListOf<Pair<String, Long>>()
        val groupInfosListedBy = mutableListOf<String>()

        /** Every rejoin sent, by whom, the links back through the group's keys, and what the next Commit is answered. */
        val rejoins = mutableListOf<Pair<String, RejoinGroupBody>>()
        val links = mutableListOf<GroupKeyLinkDto>()
        val answers = mutableListOf<GroupMembershipAnswer>()

        /** Whether the answer to the next Commit the server takes is lost on the way back. */
        var losesAnswer = false

        private val nonces = mutableSetOf<String>()
        private val queues = mutableMapOf<String, MutableList<GroupMessageDto>>()
        private val keyCopies = mutableMapOf<String, ServerKeyCopies>()
        private var messageCount = 0

        /** The copies of their group keys the server keeps for [userUuid], with their key ring, which outlives a log-out. */
        fun keyCopiesOf(userUuid: String) = keyCopies.getOrPut(userUuid) { ServerKeyCopies() }

        /** What waits for [userUuid] in the group, which outlives a log-out. */
        fun waitingFor(userUuid: String) = queues.getOrPut(userUuid) { mutableListOf() }

        /** Has [userUuid] leave, or delete their account, at [DAVE_LEFT_AT]. */
        fun departs(userUuid: String) {
            members -= userUuid
            departed += DepartedMemberDto(userUuid = userUuid, leftAt = DAVE_LEFT_AT)
            requests -= userUuid
            groupInfos -= userUuid
        }

        /**
         * Stores [commit] from [senderUuid] for every current member but them, as removing
         * [departedMemberUuids] for having departed, and takes the group one epoch further, which
         * drops every GroupInfo of the epoch it leaves.
         */
        fun stores(commit: ByteArray, senderUuid: String, departedMemberUuids: List<String>) {
            val uuid = "m${++messageCount}"

            generation += 1
            groupInfos.clear()

            (members - senderUuid).forEach { userUuid ->
                waitingFor(userUuid) += groupMessageOf(
                    uuid = uuid,
                    senderUuid = senderUuid,
                    message = commit,
                    departedMemberUuids = departedMemberUuids,
                )
            }
        }

        /**
         * A rejoin from [senderUuid]: it drops what waited for them in the group and closes their
         * request as it is stored, as the API does.
         */
        fun rejoin(senderUuid: String, body: RejoinGroupBody): GroupMembershipAnswer {
            rejoins += senderUuid to body

            if (isShut) return GroupMembershipAnswer.Failed(error = NetworkError.NOT_FOUND)

            return taken(
                senderUuid = senderUuid,
                nonce = body.nonce,
                commitMessage = body.commitMessage,
                previousKey = body.previousKey,
                keyCopy = body.keyCopy,
            ) {
                waitingFor(senderUuid).clear()
                stores(commit = Base64.Default.decode(body.commitMessage), senderUuid = senderUuid, departedMemberUuids = emptyList())
                requests -= senderUuid

                GroupMembershipAnswer.Made
            }
        }

        /** A departed removal from [senderUuid], of members the server lists as departed alone. */
        fun removeDeparted(senderUuid: String, body: RemoveDepartedMembersBody): GroupMembershipAnswer =
            taken(
                senderUuid = senderUuid,
                nonce = body.nonce,
                commitMessage = body.commitMessage,
                previousKey = body.previousKey,
                keyCopy = body.keyCopy,
            ) {
                if (!body.removedMemberUuids.all { uuid -> departed.any { it.userUuid == uuid } }) {
                    return@taken GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)
                }

                stores(
                    commit = Base64.Default.decode(body.commitMessage),
                    senderUuid = senderUuid,
                    departedMemberUuids = body.removedMemberUuids,
                )
                departed.removeAll { it.userUuid in body.removedMemberUuids }

                GroupMembershipAnswer.Made
            }

        /** The owner's removal of members by hand, for every current member, those it removes included. */
        fun remove(senderUuid: String, body: RemoveGroupMembersBody): GroupMembershipAnswer =
            taken(
                senderUuid = senderUuid,
                nonce = body.nonce,
                commitMessage = body.commitMessage,
                previousKey = body.previousKey,
                keyCopy = body.keyCopy,
            ) {
                stores(commit = Base64.Default.decode(body.commitMessage), senderUuid = senderUuid, departedMemberUuids = emptyList())
                members -= body.removedMemberUuids.toSet()
                requests -= body.removedMemberUuids.toSet()

                GroupMembershipAnswer.Made
            }

        /**
         * What the server answers a Commit from [senderUuid], [commitMessage], named by [nonce]: a
         * `404` to one who is no current member, a resend's `409`, the JSON `409` naming its epoch
         * when it is not built in the group's, else what [take] makes of it, unless a test has it
         * answer otherwise first. Taken, the link and the copy it carries are stored at the epoch it
         * leads to.
         */
        private fun taken(
            senderUuid: String,
            nonce: String,
            commitMessage: String,
            previousKey: EncryptedFieldDto?,
            keyCopy: EncryptedFieldDto,
            take: () -> GroupMembershipAnswer,
        ): GroupMembershipAnswer {
            if (senderUuid !in members) return GroupMembershipAnswer.Failed(error = NetworkError.NOT_FOUND)

            if (nonce in nonces) return GroupMembershipAnswer.AlreadyMade

            answers.removeFirstOrNull()?.let { return it }

            if (commitEpochOf(Base64.Default.decode(commitMessage)) != generation) {
                return GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = generation)
            }

            val answer = take()

            if (answer != GroupMembershipAnswer.Made) return answer

            nonces += nonce
            previousKey?.let { links += GroupKeyLinkDto(generation = generation, publisherUuid = senderUuid, previousKey = it) }
            keyCopiesOf(senderUuid).copies[GROUP] = keyCopy
            keyCopiesOf(senderUuid).generations[GROUP] = generation

            if (losesAnswer) {
                losesAnswer = false

                return GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)
            }

            return answer
        }

        /** `PUT /groups/{group_uuid}/rejoin-request`. */
        fun requestRejoin(userUuid: String): EmptyResult<NetworkError> {
            if (isShut || userUuid !in members) return Result.Error(NetworkError.NOT_FOUND)

            requests += userUuid
            requested += userUuid

            return Result.Success(Unit)
        }

        /** `GET /groups/{group_uuid}/group-infos`. */
        fun groupInfos(userUuid: String): Result<List<GroupInfoPublisherDto>, NetworkError> {
            groupInfosListedBy += userUuid

            if (isShut || userUuid !in members) return Result.Error(NetworkError.NOT_FOUND)

            return Result.Success(groupInfos.keys.map { GroupInfoPublisherDto(publisherUuid = it, epoch = generation) })
        }

        /** `GET /groups/{group_uuid}/group-infos/{publisher_uuid}`. */
        fun groupInfoOf(userUuid: String, publisherUuid: String): Result<GroupInfoDto, NetworkError> {
            if (isShut || userUuid !in members) return Result.Error(NetworkError.NOT_FOUND)

            val groupInfo = groupInfos[publisherUuid] ?: return Result.Error(NetworkError.NOT_FOUND)

            return Result.Success(
                GroupInfoDto(publisherUuid = publisherUuid, epoch = generation, groupInfo = Base64.Default.encode(groupInfo)),
            )
        }

        /**
         * `PUT /groups/{group_uuid}/group-info`, as [GroupApi.publishGroupInfo] reads it: either
         * `409`, of a GroupInfo of another epoch or one nobody asked for, stores nothing and is as
         * good as its being stored.
         */
        fun publish(userUuid: String, groupInfo: ByteArray): EmptyResult<NetworkError> {
            if (isShut || userUuid !in members) return Result.Error(NetworkError.NOT_FOUND)

            if (groupInfoEpochOf(groupInfo) != generation || (requests - userUuid).isEmpty()) return Result.Success(Unit)

            groupInfos -= userUuid
            groupInfos[userUuid] = groupInfo
            published += userUuid to generation

            return Result.Success(Unit)
        }

        /** `GET /groups/{group_uuid}/key-links`, ascending by generation, to current members alone. */
        fun links(userUuid: String): Result<List<GroupKeyLinkDto>, NetworkError> =
            if (userUuid in members) Result.Success(links.sortedBy { it.generation }) else Result.Error(NetworkError.NOT_FOUND)

        /** `PUT /groups/{group_uuid}/key-links/{generation}`, one per member and generation, its `409` as good. */
        fun publishLink(userUuid: String, generation: Long, previousKey: EncryptedFieldDto): EmptyResult<NetworkError> {
            if (userUuid !in members) return Result.Error(NetworkError.NOT_FOUND)

            if (links.none { it.generation == generation && it.publisherUuid == userUuid }) {
                links += GroupKeyLinkDto(generation = generation, publisherUuid = userUuid, previousKey = previousKey)
            }

            return Result.Success(Unit)
        }

        /** The group as the server lists it to [userUuid], one of its current members, with their copy of its key. */
        fun listing(userUuid: String): List<GroupDto> {
            if (userUuid !in members) return emptyList()

            val listed = listedGroupOf(
                uuid = GROUP,
                ownerUuid = ALICE,
                version = version,
                memberUuids = members.toList(),
                keyGeneration = generation,
                departed = departed.toList(),
            ).copy(groupInfoWanted = (requests - userUuid).isNotEmpty() && userUuid !in groupInfos)

            return listOf(keyCopiesOf(userUuid).listing(listed))
        }
    }

    /**
     * One member's device, holding the group as joined, its MLS state in [mlsGroupId], or as a
     * log-out and the sync bringing it back leave it, with none, its key [groupKey] of
     * [keyGeneration], with the group sync, changer, sender and rejoiner every device runs. Who
     * reaches it are [contacts], and its library stores into [store], when a test needs it.
     */
    private inner class Device(
        val client: MlsClient,
        val userUuid: String,
        mlsGroupId: ByteArray?,
        private val contacts: Set<String>,
        groupKey: ByteArray = firstKey,
        keyGeneration: Long = FIRST_KEY_GENERATION,
        private val store: InMemoryMlsStore? = null,
    ) {
        val groups = mutableMapOf(GROUP to heldGroup(mlsGroupId = mlsGroupId, groupKey = groupKey, keyGeneration = keyGeneration))
        val members = mutableMapOf(GROUP to server.members.map { memberOf(it) })
        val keys = mutableMapOf(keyGeneration to GroupKeyEntity(groupUuid = GROUP, generation = keyGeneration, groupKey = groupKey))
        val signatureKeys = mutableMapOf<String, ByteArray>()

        /** The changes waiting on the server, and those to be made again, see [PendingMembershipChangeEntity.isOutdated]. */
        val pending = mutableMapOf<String, PendingMembershipChangeEntity>()
        val outdated = mutableMapOf<String, PendingMembershipChangeEntity>()

        val resends = ResendsBooked()
        val keyCopies = server.keyCopiesOf(userUuid)
        private val groupKeyCopies = keyCopies.groupKeyCopies(groups)

        /** The groups each pass left short of their members as it posted the user's key into the others. */
        val handedOutBehind = mutableListOf<Set<String>>()

        /**
         * Whether the server can be asked what waits, saving the next rejoin fails, the library's
         * storage failing with it from then on, and the process dies before sending it.
         */
        var areMessagesReachable = true

        /** What the vault holds of the groups' members, or null when it cannot be read. */
        var vaultBook: MemberProfileBook? = MemberProfileBook.EMPTY

        var failsSave = false
        var failsStorageWithSave = false
        var diesBeforeSend = false

        init {
            if (GROUP !in keyCopies.copies) {
                keyCopies.copies[GROUP] = keyCopies.copyOf(groupKey = groupKey, sealedFor = GROUP, keyGeneration = keyGeneration)
                keyCopies.generations[GROUP] = keyGeneration
            }
        }

        private val sender = GroupMembershipSender(
            addMembers = { _, _ -> error("Nobody adds anybody here.") },
            removeMembers = { _, body -> server.remove(senderUuid = userUuid, body = body) },
            removeDeparted = { _, body -> server.removeDeparted(senderUuid = userUuid, body = body) },
            replaceCommit = { _, _ -> error("No Commit is refused by everybody here.") },
            rejoin = { _, body -> server.rejoin(senderUuid = userUuid, body = body) },
            group = { groups[it] },
            listPending = { pending.values + outdated.values },
            dropPending = { pending.remove(it) },
            markOutdated = { groupUuid ->
                outdated[groupUuid] = checkNotNull(pending.remove(groupUuid)).copy(isOutdated = true)
            },
            dropOutdated = { outdated.remove(it) },
            forgetRejoin = { groupUuid ->
                groups[groupUuid] = groups.getValue(groupUuid).withMlsGroupId(mlsGroupId = null)
                pending.remove(groupUuid)
            },
            replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
            recordSignatureKeys = ::record,
            rebuildWakeList = {},
            takeCommittedKey = { key ->
                keys[key.generation] = key
                groups[key.groupUuid] = groups.getValue(key.groupUuid).with(key = key, isBackedUp = true)
                pending.remove(key.groupUuid)
            },
            client = { Result.Success(client) },
            receiveAccountDeletions = {},
            resendScheduler = resends,
        )

        private val changer: GroupMembershipChanger = GroupMembershipChanger(
            userUuid = { userUuid },
            restoreContacts = { Result.Success(Unit) },
            contacts = { contacts.toList() },
            blocked = { emptyList() },
            client = { Result.Success(client) },
            keyCopySealer = keyCopies::readSealer,
            consumeKeyPackage = { error("Nobody adds anybody here.") },
            group = { groups[it] },
            hasPendingChange = { it in pending || it in outdated },
            savePending = { change -> pending[change.groupUuid] = change },
            outdatedChanges = { outdated.values.toList() },
            dropOutdated = { outdated.remove(it) },
            bookResend = sender::bookResend,
            send = sender::send,
            ownProfileKey = { null },
            contactProfileKey = { null },
            learnedProfileKey = { null },
            oweProfileKey = {},
            syncGroups = { groupSync.sync(contactsTakenIn = false) },
        )

        private val rejoiner = GroupRejoiner(
            client = { Result.Success(client) },
            keyCopySealer = keyCopies::readSealer,
            memberProfileBook = { vaultBook?.let { Result.Success(it) } ?: Result.Error(VaultError.Network(NetworkError.UNREACHABLE)) },
            groupsMissingSignatureKeys = {
                groups.values.filter { !it.isLeft && it.mlsGroupId != null && signatureKeys.isEmpty() }
            },
            recordSignatureKeys = { recorded -> recorded.forEach { signatureKeys[it.userUuid] = it.signatureKey } },
            storedGroups = { groups.values.toList() },
            group = { groups[it] },
            hasPendingChange = { it in pending || it in outdated },
            heldKeys = { groupUuid -> keys.values.filter { it.groupUuid == groupUuid } },
            listGroupInfos = { server.groupInfos(userUuid = userUuid) },
            groupInfoOf = { _, publisherUuid -> server.groupInfoOf(userUuid = userUuid, publisherUuid = publisherUuid) },
            publishGroupInfo = { _, groupInfo -> server.publish(userUuid = userUuid, groupInfo = groupInfo) },
            saveRejoin = { mlsGroupId, change ->
                if (failsSave) {
                    // The library stores into the very database, see RoomMlsStore.
                    if (failsStorageWithSave) checkNotNull(store).failing = MlsNamespace.entries.toSet()

                    error("The database fails.")
                }

                groups[change.groupUuid] = groups.getValue(change.groupUuid).withMlsGroupId(mlsGroupId = mlsGroupId)
                pending[change.groupUuid] = change
            },
            bookResend = sender::bookResend,
            send = { change, group, client ->
                if (diesBeforeSend) {
                    diesBeforeSend = false

                    GroupMembershipAnswer.Failed(error = NetworkError.UNREACHABLE)
                } else {
                    sender.send(change = change, group = group, client = client)
                }
            },
        )

        private val keyLinks = GroupKeyLinks(
            keysOwingLinks = {
                keys.values.filter { key ->
                    key.isLinkOwed && groups[key.groupUuid]?.let { !it.isLeft && it.mlsGroupId != null } == true
                }
            },
            heldKeys = { groupUuid -> keys.values.filter { it.groupUuid == groupUuid } },
            keyLinks = { server.links(userUuid = userUuid) },
            publishKeyLink = { _, generation, previousKey ->
                server.publishLink(userUuid = userUuid, generation = generation, previousKey = previousKey)
            },
            markLinkSeen = { _, generation -> keys[generation] = keys.getValue(generation).linkSeen() },
        )

        private val groupSync: GroupSync = GroupSync(
            userUuid = { userUuid },
            resendPending = { Result.Success(Unit) },
            resendMembershipChanges = sender::resendPending,
            openKeyCopies = { Result.Success(emptyMap()) },
            putUpKeyCopies = groupKeyCopies::putUp,
            restoreContacts = { Result.Success(Unit) },
            listGroups = { Result.Success(server.listing(userUuid = userUuid)) },
            storedGroups = { groups.values.toList() },
            declinedGroups = { emptyList() },
            listMessages = {
                if (areMessagesReachable) {
                    Result.Success(ListGroupMessagesResponse(messages = server.waitingFor(userUuid).toList()))
                } else {
                    Result.Error(NetworkError.NO_INTERNET)
                }
            },
            acknowledge = { _, messageUuids ->
                server.waitingFor(userUuid).removeAll { it.uuid in messageUuids }
                Result.Success(Unit)
            },
            leave = { error("Nobody declines anything here.") },
            keyLinks = { server.links(userUuid = userUuid) },
            isUnblockedContact = { it in contacts },
            client = { Result.Success(client) },
            save = { _, _, _, _ -> error("Nobody joins anything here.") },
            memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
            replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
            recordSignatureKeys = ::record,
            takeKeys = { groupUuid, taken ->
                taken.forEach { keys[it.generation] = it }

                val newest = taken.maxBy { it.generation }
                val held = groups.getValue(groupUuid)

                if (newest.generation > held.keyGeneration) groups[groupUuid] = held.with(key = newest, isBackedUp = false)
            },
            updateInformation = { _, _ -> error("Nobody edits anything here.") },
            markLeft = { groupUuid -> groups[groupUuid] = groups.getValue(groupUuid).with(isLeft = true) },
            recordDeclined = { error("Nobody declines anything here.") },
            forgetDeclined = { error("Nobody declines anything here.") },
            setAwaitingOwner = { groupUuid, isAwaitingOwner ->
                groups[groupUuid] = groups.getValue(groupUuid).with(isAwaitingOwner = isAwaitingOwner)
            },
            removeDeparted = changer::removeDepartedUnderGroupsLock,
            replaceRefused = changer::replaceUnderGroupsLock,
            remakeOutdated = changer::remakeOutdatedUnderGroupsLock,
            settleUnlisted = sender::settleUnlisted,
            keyOf = { _, _, _ -> error("Nobody edits anything here.") },
            checkKeyLinks = keyLinks::check,
            requestRejoin = { server.requestRejoin(userUuid = userUuid) },
            rejoinRestored = rejoiner::rejoinUnderGroupsLock,
            publishGroupInfos = rejoiner::publishUnderGroupsLock,
            recordMissingSignatureKeys = rejoiner::recordMissingSignatureKeys,
            fullName = { null },
            fullNameEvenIfHidden = { null },
            hasPendingChange = { it in pending },
            hasPendingCreation = { false },
            markDeleted = { error("Nobody deletes a group here.") },
            isBlocked = { false },
            setHandedProfileKey = { _, _ -> },
            takeOwnerKey = { _, _, _ -> },
            fillGap = { _, _ -> },
            restoreMemberProfiles = { Result.Success(Unit) },
            readMemberNames = { Result.Success(Unit) },
            backUpMemberProfiles = { Result.Success(Unit) },
            handOutProfileKey = { behind ->
                handedOutBehind += behind.toSet()
                Result.Success(Unit)
            },
            rebuildWakeList = {},
            now = { now },
            resendScheduler = resends,
        )

        suspend fun sync(): GroupSyncPass = groupSync.sync(contactsTakenIn = true)

        /** Has the user remove [userUuid] from the group by hand, as its settings do. */
        suspend fun remove(userUuid: String) = changer.remove(groupUuid = GROUP, userUuids = setOf(userUuid))

        fun group() = groups.getValue(GROUP)

        fun memberRows() = members.getValue(GROUP).toSet()

        /** Records [recorded] as the members' signature keys, as `GroupDao.upsertSignatureKeys` does. */
        private fun record(recorded: List<GroupMemberSignatureKeyEntity>) {
            recorded.forEach { signatureKeys[it.userUuid] = it.signatureKey }
        }
    }
}

/** The group as a device holds it, joined, or brought back after a log-out with no MLS state. */
private fun heldGroup(mlsGroupId: ByteArray?, groupKey: ByteArray, keyGeneration: Long) = GroupEntity(
    uuid = GROUP,
    title = FIELDS.title,
    description = FIELDS.description,
    currency = FIELDS.currency,
    infoRevision = FIRST_GROUP_REVISION,
    ownerUuid = ALICE,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    keyGeneration = keyGeneration,
    isBackedUp = true,
    isLeft = false,
    handedProfileKeyHash = null,
)

private fun memberOf(userUuid: String) = GroupMemberEntity(userUuid = userUuid, groupUuid = GROUP, isAdmin = userUuid == ALICE)

private fun rowsOf(vararg userUuids: String) = userUuids.mapTo(HashSet(), ::memberOf)

/** When [iso], RFC 3339 in UTC to the millisecond, is, in milliseconds since the epoch. */
@OptIn(ExperimentalTime::class)
private fun millisOf(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

/**
 * The epoch of the GroupInfo [groupInfo], as the server reads it: the MLS version and the wire
 * format, `mls_group_info`, then the group context's version, cipher suite and group id, and its
 * epoch, see RFC 9420, §12.4.3.
 */
private fun groupInfoEpochOf(groupInfo: ByteArray): Long {
    fun byteAt(at: Int) = groupInfo[at].toLong() and 0xff

    check(byteAt(3) == 4L) { "Not a GroupInfo." }

    var at = 8
    val first = byteAt(at)

    val (length, prefix) = when (first shr 6) {
        0L -> (first and 0x3f) to 1
        1L -> (((first and 0x3f) shl 8) or byteAt(at + 1)) to 2
        else -> ((((first and 0x3f) shl 24) or (byteAt(at + 1) shl 16) or (byteAt(at + 2) shl 8) or byteAt(at + 3))) to 4
    }

    at += prefix + length.toInt()

    return (0 until 8).fold(0L) { epoch, index -> (epoch shl 8) or byteAt(at + index) }
}

private fun GroupEntity.with(
    key: GroupKeyEntity? = null,
    isBackedUp: Boolean = this.isBackedUp,
    isAwaitingOwner: Boolean = this.isAwaitingOwner,
    isLeft: Boolean = this.isLeft,
) = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    infoRevision = infoRevision,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = key?.groupKey ?: groupKey,
    keyGeneration = key?.generation ?: keyGeneration,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
    isAwaitingOwner = isAwaitingOwner,
)

/** [this] as `GroupDao.setMlsGroupId` leaves it. */
private fun GroupEntity.withMlsGroupId(mlsGroupId: ByteArray?) = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    infoRevision = infoRevision,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    keyGeneration = keyGeneration,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
    isAwaitingOwner = isAwaitingOwner,
)

/** [this] once the server is seen to hold a link at its generation leading back, as `GroupDao.markLinkSeen` leaves it. */
private fun GroupKeyEntity.linkSeen() =
    GroupKeyEntity(groupUuid = groupUuid, generation = generation, groupKey = groupKey, isRecovered = isRecovered)
