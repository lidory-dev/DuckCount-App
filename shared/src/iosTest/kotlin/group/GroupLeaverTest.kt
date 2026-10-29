package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.addMembersTo
import mls.applyPendingCommitIn
import mls.encryptApplicationMessage
import mls.holdsGroup
import mls.inviteeKeyPackage
import mls.removeMembersFrom
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OWN_GROUP = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

/** What KSafe throws as anything stored with a key a locked device cannot use is read. */
private const val DEVICE_LOCKED = "KSafe: Cannot access Keystore key - device is locked"

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/** What the server makes of a leave Bob sends, see [GroupLeaverTest.serverLeave]. */
private enum class ServerLeave {
    /** Taken, and answered. */
    TAKEN,

    /** Never heard: the request did not get there. */
    UNHEARD,

    /** Taken, and the answer lost on its way back. */
    ANSWER_LOST,

    /** Answered `404` by the proxy in front of the server as it is redeployed, never getting there. */
    PROXIED,
}

/**
 * What becomes of a group Bob leaves from its settings, see [GroupLeaver], and what his group sync
 * makes of it afterwards, see [GroupSync]. Alice created the group with him and Carol in it, and
 * changes it as [GroupMembershipChanger] and [GroupMembershipSender] do on her device, every MLS
 * client the library's own. The server takes a leave as the API does: a current member is taken
 * out, a former member's leave changes nothing, and what waited for either is dropped, unless a
 * test has it go otherwise. It lists the group to its current members alone, with the copy of its
 * key it holds for Bob, which it drops as it takes his leave, or as Alice removes him, see
 * [ServerKeyCopies], and hands a Commit to every current member but Alice. The database and the
 * key ring are stood in for.
 *
 * Alice and Dave are Bob's contacts, both pinned. Carol is nobody to Bob.
 */
class GroupLeaverTest {

    private val bobStore = InMemoryMlsStore()

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private var bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = bobStore)
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

    private val contacts = setOf(ALICE, DAVE)
    private val names = mapOf(ALICE to "Alice", DAVE to "Dave")

    /** Who is signed in. */
    private var selfUuid: String? = BOB

    /** Alice's MLS group, the key she last handed over, and the group's information as the server holds it. */
    private var aliceGroupId: ByteArray? = null
    private var groupKey = generateSymmetricEncryptionKey()
    private var serverVersion: SealedGroupVersion? = null

    /** Who the server holds as the group's current members. */
    private val serverMembers = mutableListOf<String>()

    /** What waits for Bob in each group, and what he asked for and acknowledged. */
    private val waiting = mutableMapOf<String, MutableList<GroupMessageDto>>()
    private val fetched = mutableListOf<String>()
    private val acknowledged = mutableMapOf<String, MutableList<String>>()
    private var messageCount = 0

    private val groups = mutableMapOf<String, GroupEntity>()
    private val members = mutableMapOf<String, List<GroupMemberEntity>>()
    private val declined = mutableSetOf<String>()

    /** Whether recording the leave fails, as the database failing would. */
    private var isDatabaseFailing = false

    /**
     * Whether Bob's device is locked, which leaves who is signed in, and the keys his MLS client
     * opens with, out of the leave's reach, and what runs once the leave is recorded.
     */
    private var isDeviceLocked = false
    private var onRecorded: () -> Unit = {}

    /** The copies of Bob's group keys the server holds, and the key ring they are sealed with. */
    private val server = ServerKeyCopies()
    private val keyCopies = server.groupKeyCopies(groups)

    /** The groups the leave dropped Bob's copy of the key of, in order. */
    private val keyCopyDeletions = mutableListOf<String>()

    /**
     * The groups Bob sent a leave for, in order, what the server makes of each one in turn, taken
     * otherwise, and what runs as each is sent.
     */
    private val left = mutableListOf<String>()
    private val serverLeaves = ArrayDeque<ServerLeave>()
    private var onLeave: suspend () -> Unit = {}

    /** What runs as the sync lists Bob's groups, under the lock it holds. */
    private var onListing: suspend () -> Unit = {}

    private val resends = ResendsBooked()

    private val groupSync = GroupSync(
        userUuid = { selfUuid },
        resendPending = { Result.Success(Unit) },
        resendMembershipChanges = { Result.Success(Unit) },
        openKeyCopies = keyCopies::open,
        putUpKeyCopies = keyCopies::putUp,
        restoreContacts = { Result.Success(Unit) },
        listGroups = {
            onListing()
            Result.Success(listOfNotNull(listedGroup()))
        },
        storedGroups = { groups.values.toList() },
        declinedGroups = { declined.toList() },
        listMessages = { groupUuid ->
            fetched += groupUuid
            Result.Success(ListGroupMessagesResponse(messages = waiting[groupUuid].orEmpty().toList()))
        },
        acknowledge = { groupUuid, messageUuids ->
            acknowledged.getOrPut(groupUuid) { mutableListOf() } += messageUuids
            waiting[groupUuid]?.removeAll { it.uuid in messageUuids }
            Result.Success(Unit)
        },
        leave = ::serverLeave,
        isUnblockedContact = { it in contacts },
        client = { Result.Success(bob) },
        save = { group, groupMembers ->
            groups[group.uuid] = group
            members[group.uuid] = groupMembers
        },
        memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        updateKey = { groupUuid, key ->
            groups[groupUuid] = groups.getValue(groupUuid).with(groupKey = key, isBackedUp = false)
        },
        updateInformation = { _, _ -> error("Alice edits nothing here.") },
        markLeft = { groupUuid -> groups[groupUuid] = groups.getValue(groupUuid).with(isLeft = true) },
        recordDeclined = { groupUuid -> declined += groupUuid },
        forgetDeclined = { groupUuid -> declined -= groupUuid },
        removeDeparted = { _, _ -> error("Bob created no group the server lists here.") },
        fullName = { names[it] },
        // What becomes of the keys to the members' profiles is GroupSyncProfileKeysTest's.
        hasPendingChange = { false },
        isBlocked = { false },
        setHandedProfileKey = { _, _ -> },
        takeOwnerKey = { _, _, _ -> },
        fillGap = { _, _ -> },
        restoreMemberProfiles = { Result.Success(Unit) },
        readMemberNames = { Result.Success(Unit) },
        backUpMemberProfiles = { Result.Success(Unit) },
        handOutProfileKey = { Result.Success(Unit) },
        resendScheduler = resends,
    )

    private val leaver = GroupLeaver(
        userUuid = {
            check(!isDeviceLocked) { DEVICE_LOCKED }
            selfUuid
        },
        group = { groups[it] },
        markLeftAndOweLeave = { groupUuid ->
            check(!isDatabaseFailing) { "The database is failing." }
            groups[groupUuid] = groups.getValue(groupUuid).with(isLeft = true)
            declined += groupUuid
            onRecorded()
        },
        client = {
            check(!isDeviceLocked) { DEVICE_LOCKED }
            Result.Success(bob)
        },
        deleteKeyCopy = { groupUuid ->
            keyCopyDeletions += groupUuid
            server.copies.remove(groupUuid)
            Result.Success(Unit)
        },
        sendLeave = ::serverLeave,
        forgetOwedLeave = { groupUuid -> declined -= groupUuid },
        resendScheduler = resends,
    )

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
        bob.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())
    }

    @Test
    fun `a member leaving marks the group left and deletes its state and its key's copy before the leave is sent`() =
        runBlocking<Unit> {
            joined()

            val mlsGroupId = bobGroup()
            var whenSent: List<Boolean>? = null

            onLeave = {
                whenSent = listOf(
                    groups.getValue(GROUP).isLeft,
                    GROUP in declined,
                    bob.holdsGroup(mlsGroupId = mlsGroupId),
                    GROUP in server.copies,
                )
            }

            assertEquals(Result.Success(Unit), leaver.leave(GROUP))

            assertEquals(listOf(true, true, false, false), whenSent)

            val group = groups.getValue(GROUP)

            assertTrue(group.isLeft)
            assertContentEquals(mlsGroupId, group.mlsGroupId)
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
            assertFalse(bob.holdsGroup(mlsGroupId = mlsGroupId))
            assertEquals(listOf(GROUP), keyCopyDeletions)
            assertTrue(GROUP !in server.copies)
            assertEquals(listOf(GROUP), left)
            assertTrue(declined.isEmpty())
            assertTrue(BOB !in serverMembers)
            assertTrue(resends.seconds.isEmpty())
        }

    @Test
    fun `a group left is neither drained nor reported at any later sync`() = runBlocking<Unit> {
        joined()
        leaver.leave(GROUP)

        repeat(2) {
            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(pass.removed.isEmpty())
        }

        assertTrue(fetched.isEmpty())
        assertEquals(listOf(GROUP), left)
        assertTrue(groups.getValue(GROUP).isLeft)
    }

    /** The leave is the user's once recorded: the network only decides when the server hears it. */
    @Test
    fun `a leave the server does not hear leaves the group left and is booked and sent by the next sync`() =
        runBlocking<Unit> {
            joined()

            serverLeaves += ServerLeave.UNHEARD

            assertEquals(Result.Success(Unit), leaver.leave(GROUP))

            assertTrue(groups.getValue(GROUP).isLeft)
            assertEquals(setOf(GROUP), declined)
            assertEquals(listOf(60L), resends.seconds)
            assertTrue(BOB in serverMembers)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(pass.removed.isEmpty())
            assertEquals(listOf(GROUP, GROUP), left)
            assertTrue(declined.isEmpty())
            assertTrue(BOB !in serverMembers)
        }

    /**
     * The proxy answers the leave as the server is redeployed, and again as the next sync sends
     * it, the server listing the group to Bob all the while: the leave stays owed and booked, sent
     * again, and the group is not taken in again. Once Alice removes him, the server lists the
     * group to him no more, and the leave answered 404 then is forgotten.
     */
    @Test
    fun `a leave answered 404 stays owed and is sent again until the group is no longer listed`() =
        runBlocking<Unit> {
            joined()

            serverLeaves += listOf(ServerLeave.PROXIED, ServerLeave.PROXIED, ServerLeave.PROXIED)

            assertEquals(Result.Success(Unit), leaver.leave(GROUP))

            assertTrue(groups.getValue(GROUP).isLeft)
            assertEquals(setOf(GROUP), declined)
            assertEquals(listOf(60L), resends.seconds)
            assertTrue(BOB in serverMembers)

            val listedPass = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Network(NetworkError.NOT_FOUND), listedPass.failure)
            assertTrue(listedPass.joined.isEmpty())
            assertTrue(listedPass.removed.isEmpty())
            assertTrue(fetched.isEmpty())
            assertEquals(listOf(GROUP, GROUP), left)
            assertEquals(setOf(GROUP), declined)
            assertEquals(listOf(60L, 60L), resends.seconds)

            aliceRemoves(BOB)

            val unlistedPass = groupSync.sync(contactsTakenIn = true)

            assertNull(unlistedPass.failure)
            assertTrue(unlistedPass.removed.isEmpty())
            assertEquals(listOf(GROUP, GROUP, GROUP), left)
            assertTrue(declined.isEmpty())
            assertEquals(listOf(60L, 60L), resends.seconds)
            assertTrue(groups.getValue(GROUP).isLeft)
        }

    /**
     * Bob's storage fails as the leave deletes the group's state, and the server does not hear the
     * leave either: what Alice sends there meanwhile waits for him unread, and the state still held
     * is deleted before she lets him back in.
     */
    @Test
    fun `a leave owed with the state still held is sent by the sync without the group being drained`() =
        runBlocking<Unit> {
            joined()

            val mlsGroupId = bobGroup()

            bobStore.failing = setOf(MlsNamespace.METADATA)
            serverLeaves += ServerLeave.UNHEARD

            assertEquals(Result.Success(Unit), leaver.leave(GROUP))

            bobStore.failing = emptySet()

            assertTrue(bob.holdsGroup(mlsGroupId = mlsGroupId))

            aliceAdds(dave)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.removed.isEmpty())
            assertTrue(GROUP !in fetched)
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
            assertEquals(listOf(GROUP, GROUP), left)
            assertTrue(declined.isEmpty())
            assertTrue(GROUP !in waiting)

            aliceRemoves(BOB)
            aliceRotates()
            aliceAdds(bob)

            val rejoined = groupSync.sync(contactsTakenIn = true)

            assertNull(rejoined.failure)
            assertEquals(listOf(GROUP), rejoined.joined.map { it.groupUuid })
            assertFalse(groups.getValue(GROUP).isLeft)
        }

    /** Nobody else could change the group's members, and the server refuses its owner anyway. */
    @Test
    fun `the creator leaving is refused and writes nothing`() = runBlocking<Unit> {
        groups[OWN_GROUP] = groupOf(uuid = OWN_GROUP, ownerUuid = BOB, mlsGroupId = OWN_GROUP.encodeToByteArray())
        server.copies[OWN_GROUP] = server.copyOf(groupKey = groupKey, sealedFor = OWN_GROUP)

        assertEquals(Result.Error(GroupLeaveError.IsCreator), leaver.leave(OWN_GROUP))

        assertFalse(groups.getValue(OWN_GROUP).isLeft)
        assertTrue(declined.isEmpty())
        assertTrue(keyCopyDeletions.isEmpty())
        assertTrue(OWN_GROUP in server.copies)
        assertTrue(left.isEmpty())
    }

    @Test
    fun `a leave that cannot be recorded writes nothing and sends nothing`() = runBlocking<Unit> {
        joined()

        val mlsGroupId = bobGroup()

        isDatabaseFailing = true

        assertEquals(Result.Error(GroupLeaveError.Storage), leaver.leave(GROUP))

        assertFalse(groups.getValue(GROUP).isLeft)
        assertTrue(declined.isEmpty())
        assertTrue(bob.holdsGroup(mlsGroupId = mlsGroupId))
        assertTrue(keyCopyDeletions.isEmpty())
        assertTrue(left.isEmpty())
    }

    @Test
    fun `a group this device does not hold is not left`() = runBlocking<Unit> {
        assertEquals(Result.Error(GroupLeaveError.NotHeld), leaver.leave(GROUP))

        assertTrue(left.isEmpty())
    }

    @Test
    fun `nobody signed in leaves nothing`() = runBlocking<Unit> {
        joined()

        selfUuid = null

        assertEquals(Result.Error(GroupLeaveError.NoSession), leaver.leave(GROUP))

        assertFalse(groups.getValue(GROUP).isLeft)
        assertTrue(left.isEmpty())
    }

    /** The leave waited for a sync as the screen went off, say: nothing is done, for Bob to ask again. */
    @Test
    fun `a device locked before the leave is recorded writes nothing and sends nothing`() = runBlocking<Unit> {
        joined()

        val mlsGroupId = bobGroup()

        isDeviceLocked = true

        assertEquals(Result.Error(GroupLeaveError.DeviceLocked), leaver.leave(GROUP))

        assertFalse(groups.getValue(GROUP).isLeft)
        assertTrue(declined.isEmpty())
        assertTrue(bob.holdsGroup(mlsGroupId = mlsGroupId))
        assertTrue(keyCopyDeletions.isEmpty())
        assertTrue(left.isEmpty())
    }

    /**
     * The device locks once the leave is recorded, before this process first opened Bob's MLS
     * client: the state stays held, and the leave, which the locked device cannot send, is booked
     * and sent by the next sync.
     */
    @Test
    fun `a device locked once the leave is recorded leaves the group left and the leave booked`() =
        runBlocking<Unit> {
            joined()

            val mlsGroupId = bobGroup()

            onRecorded = { isDeviceLocked = true }
            serverLeaves += ServerLeave.UNHEARD

            assertEquals(Result.Success(Unit), leaver.leave(GROUP))

            assertTrue(groups.getValue(GROUP).isLeft)
            assertEquals(setOf(GROUP), declined)
            assertTrue(bob.holdsGroup(mlsGroupId = mlsGroupId))
            assertEquals(listOf(60L), resends.seconds)

            isDeviceLocked = false

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.removed.isEmpty())
            assertEquals(listOf(GROUP, GROUP), left)
            assertTrue(declined.isEmpty())
            assertTrue(BOB !in serverMembers)
        }

    /**
     * Alice removes Bob as he leaves, the Commit waiting for him unread: the server drops it with
     * his leave, a former member's, and nothing ever tells him she removed him.
     */
    @Test
    fun `leaving with a removal waiting reports no removal at any later sync`() = runBlocking<Unit> {
        joined()
        aliceRemoves(BOB)

        assertEquals(Result.Success(Unit), leaver.leave(GROUP))

        assertTrue(GROUP !in waiting)

        repeat(2) {
            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.removed.isEmpty())
        }

        assertTrue(fetched.isEmpty())
        assertTrue(groups.getValue(GROUP).isLeft)
    }

    /** That sync told him of the removal, and the server holds him no member already. */
    @Test
    fun `a group the sync just took the user out of is not left again`() = runBlocking<Unit> {
        joined()
        aliceRemoves(BOB)

        assertEquals(listOf(GROUP), groupSync.sync(contactsTakenIn = true).removed.map { it.groupUuid })

        assertEquals(Result.Success(Unit), leaver.leave(GROUP))

        assertTrue(left.isEmpty())
        assertTrue(declined.isEmpty())
        assertTrue(keyCopyDeletions.isEmpty())
    }

    /**
     * A lock-step with a sync that reads the Commit removing Bob as he confirms: the leave waits
     * for the sync, and finds the group left once it reads it again.
     */
    @Test
    fun `a leave asked for during a sync waits for it and reads the group as it left it`() = runBlocking<Unit> {
        joined()
        aliceRemoves(BOB)

        val gate = CompletableDeferred<Unit>()
        var isListing = false

        onListing = {
            isListing = true
            gate.await()
        }

        val syncing = async { groupSync.sync(contactsTakenIn = true) }

        while (!isListing) yield()

        val leaving = async { leaver.leave(GROUP) }

        yield()

        assertFalse(groups.getValue(GROUP).isLeft)
        assertTrue(declined.isEmpty())

        onListing = {}
        gate.complete(Unit)

        val pass = syncing.await()

        assertEquals(Result.Success(Unit), leaving.await())
        assertEquals(listOf(GROUP), pass.removed.map { it.groupUuid })
        assertTrue(left.isEmpty())
        assertTrue(declined.isEmpty())
    }

    /**
     * As a log-out leaves Bob: the copy of the key listed, no MLS state. Unlisted once he left, it
     * would otherwise be taken for a group Alice removed him from.
     */
    @Test
    fun `a group brought back after a log-out can be left and is never reported as a removal`() =
        runBlocking<Unit> {
            broughtBack()

            assertEquals(Result.Success(Unit), leaver.leave(GROUP))

            assertTrue(groups.getValue(GROUP).isLeft)
            assertTrue(GROUP !in server.copies)
            assertEquals(listOf(GROUP), left)
            assertTrue(declined.isEmpty())

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.removed.isEmpty())
            assertTrue(pass.joined.isEmpty())
            assertTrue(groups.getValue(GROUP).isLeft)
        }

    /** Alice's device removes him for leaving, rotating the key, and adds him back later. */
    @Test
    fun `the user added back after leaving joins again and is told about it`() = runBlocking<Unit> {
        joined()
        leaver.leave(GROUP)

        aliceRemoves(BOB)
        aliceRotates()

        val welcome = assertNotNull(aliceAdds(bob))

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(
            listOf(GroupArrival(groupUuid = GROUP, title = "Ski trip", adderUuid = ALICE, adderFullName = "Alice")),
            pass.joined,
        )
        assertTrue(pass.removed.isEmpty())

        val group = groups.getValue(GROUP)

        assertFalse(group.isLeft)
        assertContentEquals(groupKey, group.groupKey)
        assertContentEquals(groupKey, server.keyOf(GROUP))
        assertTrue(bob.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))
        assertEquals(listOf(welcome), acknowledged[GROUP]?.toList())
    }

    /**
     * The server took the leave and its answer was lost, and Alice added Bob back before the leave
     * went out again: it leaves the group again, the Welcome dropped with it, which is accepted.
     */
    @Test
    fun `the user added back while the leave is owed leaves again`() = runBlocking<Unit> {
        joined()

        serverLeaves += ServerLeave.ANSWER_LOST

        leaver.leave(GROUP)

        assertEquals(listOf(60L), resends.seconds)

        aliceRemoves(BOB)
        aliceRotates()
        aliceAdds(bob)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertTrue(pass.removed.isEmpty())
        assertTrue(groups.getValue(GROUP).isLeft)
        assertEquals(listOf(GROUP, GROUP), left)
        assertTrue(declined.isEmpty())
        assertTrue(BOB !in serverMembers)
        assertTrue(GROUP !in waiting)
    }

    /**
     * A log-out forgets the leave owed with everything else this device holds: with no copy of the
     * key listed to bring the group back with, and no Welcome waiting, the next session declines
     * it, and leaves it again.
     */
    @Test
    fun `a leave owed through a log-out is declined and sent by the next session`() = runBlocking<Unit> {
        joined()

        serverLeaves += ServerLeave.UNHEARD

        leaver.leave(GROUP)

        assertTrue(GROUP !in server.copies)

        loggedOut()

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertTrue(pass.removed.isEmpty())
        assertTrue(groups.isEmpty())
        assertTrue(declined.isEmpty())
        assertEquals(listOf(GROUP, GROUP), left)
        assertTrue(BOB !in serverMembers)
    }

    /**
     * What the server makes of Bob leaving [groupUuid], see [serverLeaves]: a current member taken
     * out, a former member's leave changing nothing, and what waited for him dropped either way,
     * as the API does, unless it never heard the leave, the proxy answering for it included.
     */
    private suspend fun serverLeave(groupUuid: String): GroupLeaveAnswer {
        onLeave()
        left += groupUuid

        val outcome = serverLeaves.removeFirstOrNull() ?: ServerLeave.TAKEN

        if (outcome == ServerLeave.UNHEARD) return GroupLeaveAnswer.Failed(NetworkError.NO_INTERNET)

        if (outcome == ServerLeave.PROXIED) return GroupLeaveAnswer.NotMember

        serverMembers -= BOB
        waiting.remove(groupUuid)
        server.copies.remove(groupUuid)

        return if (outcome == ServerLeave.ANSWER_LOST) {
            GroupLeaveAnswer.Failed(NetworkError.REQUEST_TIMEOUT)
        } else {
            GroupLeaveAnswer.Left
        }
    }

    /** Has the sync join Alice's group from its Welcome, putting its key's copy up, and forgets what it fetched. */
    private suspend fun joined() {
        aliceCreates()

        assertEquals(listOf(GROUP), groupSync.sync(contactsTakenIn = true).joined.map { it.groupUuid })
        assertContentEquals(groupKey, server.keyOf(GROUP))

        fetched.clear()
        acknowledged.clear()
    }

    /**
     * Has Bob hold [GROUP] as the sync brings it back after a log-out: no MLS state, the Welcome
     * long acknowledged, and the copy of its key on the server.
     */
    private fun broughtBack() {
        aliceCreates()

        waiting.getValue(GROUP).clear()
        server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP)
        groups[GROUP] = groupOf(uuid = GROUP, ownerUuid = ALICE, mlsGroupId = null)
        members[GROUP] = rowsOf(ALICE, BOB, CAROL).toList()
    }

    /** Has a log-out take everything this device holds of Bob's groups, his MLS state included. */
    private fun loggedOut() {
        groups.clear()
        members.clear()
        declined.clear()

        bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
    }

    /**
     * Has Alice create [GROUP] with Bob and Carol, handing them [groupKey], and the server list it
     * with the Welcome waiting for Bob.
     */
    private fun aliceCreates() {
        val creation = alice.createGroup(
            groupUuid = GROUP,
            invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(groupKey = groupKey, profileKeys = emptyMap()),
        )

        aliceGroupId = creation.mlsGroupId
        serverMembers += listOf(ALICE, BOB, CAROL)
        serverVersion = FIELDS.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP)

        post(ciphertext = creation.welcome)
    }

    /**
     * Has Alice add [invitee], and the server deliver the Commit to the members already in the group
     * but her, and the Welcome to [invitee]: returns the UUID of whichever reached Bob, if any.
     */
    private suspend fun aliceAdds(invitee: MlsClient): String? {
        val addition = alice.addMembersTo(
            mlsGroupId = aliceGroup(),
            invitees = listOf(invitee.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(groupKey = groupKey, profileKeys = emptyMap()),
        )

        alice.applyPendingCommitIn(mlsGroupId = aliceGroup())

        val delivered = deliver(addition.commit)

        serverMembers += invitee.userUuid()

        return if (invitee == bob) post(ciphertext = addition.welcome) else delivered
    }

    /**
     * Has Alice remove [userUuid], as her sync does once the server lists them no more, and the
     * server deliver the Commit to every current member but her, then take them off the group,
     * dropping Bob's copy of its key with him.
     */
    private suspend fun aliceRemoves(userUuid: String) {
        val commit = alice.removeMembersFrom(mlsGroupId = aliceGroup(), userUuids = listOf(userUuid))

        alice.applyPendingCommitIn(mlsGroupId = aliceGroup())

        deliver(commit)

        serverMembers -= userUuid

        if (userUuid == BOB) server.copies.remove(GROUP)
    }

    /**
     * Has Alice hand the members a new key as [GroupMembershipSender] does once a removal is applied,
     * and the server hold the group's information sealed again under it.
     */
    private suspend fun aliceRotates() {
        val newKey = generateSymmetricEncryptionKey()
        val message = alice.encryptApplicationMessage(mlsGroupId = aliceGroup(), payload = groupKeyMessageOf(newKey))

        deliver(message)

        groupKey = newKey
        serverVersion = FIELDS.sealedAs(creator = alice, groupKey = newKey, groupUuid = GROUP)
    }

    /** Has the server hand [message] from Alice to Bob, provided he is a current member, and returns its UUID then. */
    private fun deliver(message: ByteArray): String? = if (BOB in serverMembers) post(ciphertext = message) else null

    /** Has the server put [ciphertext] from Alice in Bob's queue for [GROUP], and returns its UUID. */
    private fun post(ciphertext: ByteArray): String {
        val uuid = "m${++messageCount}"

        waiting.getOrPut(GROUP) { mutableListOf() } += GroupMessageDto(
            uuid = uuid,
            senderUserUuid = ALICE,
            ciphertext = Base64.Default.encode(ciphertext),
            createdAt = "2026-10-28T12:00:00Z",
        )

        return uuid
    }

    /** The group as the server lists it to Bob: to its current members alone. */
    private fun listedGroup(): GroupDto? {
        val version = serverVersion ?: return null

        if (BOB !in serverMembers) return null

        return listedGroupOf(
            uuid = GROUP,
            ownerUuid = ALICE,
            version = version,
            memberUuids = serverMembers.toList(),
            keyCopy = server.copies[GROUP],
        )
    }

    private fun bobGroup() = assertNotNull(groups[GROUP]?.mlsGroupId)

    private fun aliceGroup() = assertNotNull(aliceGroupId)

    /** A group of [ownerUuid]'s as this device holds it, the user still in it. */
    private fun groupOf(uuid: String, ownerUuid: String, mlsGroupId: ByteArray?) = GroupEntity(
        uuid = uuid,
        title = FIELDS.title,
        description = FIELDS.description,
        currency = FIELDS.currency,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = ownerUuid,
        mlsGroupId = mlsGroupId,
        groupKey = groupKey,
        isBackedUp = true,
        isLeft = false,
        handedProfileKeyHash = null,
    )
}

private fun rowsOf(vararg userUuids: String) =
    userUuids.mapTo(HashSet()) { GroupMemberEntity(userUuid = it, groupUuid = GROUP, isAdmin = it == ALICE) }

private fun GroupEntity.with(
    groupKey: ByteArray = this.groupKey,
    isBackedUp: Boolean = this.isBackedUp,
    isLeft: Boolean = this.isLeft,
) = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    infoRevision = infoRevision,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
