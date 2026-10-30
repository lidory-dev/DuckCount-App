package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.addMembersTo
import mls.applyPendingCommitIn
import mls.encryptApplicationMessage
import mls.holdsGroup
import mls.inviteeKeyPackage
import mls.processGroupMessageIn
import mls.removeMembersFrom
import mls.rosterOf
import networking.pageOf
import networking.readWholeListing
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.EmptyResult
import util.NetworkError
import util.Result
import util.map
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What Bob's group sync makes of what Alice, who created a group with him and Carol in it, changes
 * of it: the members she adds and removes, Bob among them, and the keys she hands over, with every
 * MLS client made through the library itself, and every change built as [GroupMembershipChanger]
 * and [GroupMembershipSender] build them on her device. The server delivers each one as the API
 * does: a Commit to every current member but her, those it removes included, a Welcome to those it
 * adds, a key message to the members who remain, and the group listed to its current members alone,
 * with the copy of its key it holds for Bob, which it drops as Alice removes him, or as he leaves,
 * see [ServerKeyCopies]. The database and the key ring are stood in for.
 *
 * Alice and Dave are Bob's contacts, both pinned. Carol is nobody to Bob, and Mallory a stranger.
 */
class GroupSyncMembershipTest {

    private val bobStore = InMemoryMlsStore()

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = bobStore)
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())
    private val mallory = MlsClient(userUuid = MALLORY, secret = ByteArray(32) { 7 }, store = InMemoryMlsStore())

    private var contacts = setOf(ALICE, DAVE)
    private val names = mapOf(ALICE to "Alice", DAVE to "Dave")

    /**
     * Alice's MLS group, the key she last handed over and its generation, the fields as the server
     * holds them, and the links back through the group's keys its rotations stored, see
     * [GroupKeyChain].
     */
    private var aliceGroupId: ByteArray? = null
    private var groupKey = generateSymmetricEncryptionKey()
    private var keyGeneration = FIRST_KEY_GENERATION
    private var serverVersion: SealedGroupVersion? = null
    private val serverLinks = mutableListOf<GroupKeyLinkDto>()

    /** Who the server holds as the group's current members, and whether it lists the group to Bob anyway. */
    private val serverMembers = mutableListOf<String>()
    private var isListedRegardless = false

    /** What waits for Bob in each group, and what he asked for and acknowledged. */
    private val waiting = mutableMapOf<String, MutableList<GroupMessageDto>>()
    private val fetched = mutableListOf<String>()
    private val acknowledged = mutableMapOf<String, MutableList<String>>()
    private var isAcknowledgingFailing = false
    private var messageCount = 0

    /**
     * How many messages the server lists a page, the sync reading every page as
     * `GroupApi.getGroupMessages` does: all of them on one unless a test says otherwise.
     */
    private var messagesPerPage = Int.MAX_VALUE

    private val groups = mutableMapOf<String, GroupEntity>()
    private val members = mutableMapOf<String, List<GroupMemberEntity>>()
    private val declined = mutableSetOf<String>()

    /**
     * Every generation of each group's key Bob's device holds, see `GroupKeyEntity`, and the
     * signature key recorded for each of its members, see `GroupMemberSignatureKeyEntity`, as the
     * database keeps them: none replaced by the next, nor dropped as a member goes.
     */
    private val heldKeys = mutableMapOf<String, MutableMap<Long, ByteArray>>()
    private val signatureKeys = mutableMapOf<String, MutableMap<String, ByteArray>>()

    /** The groups Bob left on the server, in order. */
    private val left = mutableListOf<String>()

    /**
     * The copies of Bob's group keys the server holds, and the key ring they are sealed with,
     * which a locked device cannot read, see [ServerKeyCopies.isDeviceLocked].
     */
    private val server = ServerKeyCopies()
    private val keyCopies = server.groupKeyCopies(groups)

    /** The members of each group as the sync rebuilt who may wake Bob each time, see [push.WakeList]. */
    private val wakeListRebuilds = mutableListOf<Map<String, List<GroupMemberEntity>>>()

    /** What runs as the sync posts the user's own key, once every group is caught up. */
    private var onHandOut: suspend () -> Unit = {}

    /** The Welcome Alice's creation let Carol in with. */
    private var carolsWelcome: ByteArray? = null

    /** What sending the pending membership changes again comes to, and what each pass ran, in order. */
    private var membershipResend: EmptyResult<GroupSyncError> = Result.Success(Unit)
    private val steps = mutableListOf<String>()

    /** What Alice does once the server listed Bob's groups to the next sync, as it runs. */
    private var afterListing: (suspend () -> Unit)? = null

    private val groupSync = GroupSync(
        userUuid = { BOB },
        resendPending = {
            steps += "creations"
            Result.Success(Unit)
        },
        resendMembershipChanges = {
            steps += "membership changes"
            membershipResend
        },
        openKeyCopies = keyCopies::open,
        putUpKeyCopies = { listed ->
            steps += "key copies"
            keyCopies.putUp(listed)
        },
        restoreContacts = { Result.Success(Unit) },
        listGroups = {
            val listed = listOfNotNull(listedGroup())

            afterListing?.let { change ->
                afterListing = null
                change()
            }

            Result.Success(listed)
        },
        storedGroups = { groups.values.toList() },
        declinedGroups = { declined.toList() },
        listMessages = { groupUuid ->
            fetched += groupUuid
            val listed = waiting[groupUuid].orEmpty().toList()

            readWholeListing { cursor -> Result.Success(pageOf(items = listed, cursor = cursor, perPage = messagesPerPage)) }
                .map { ListGroupMessagesResponse(messages = it) }
        },
        acknowledge = { groupUuid, messageUuids ->
            if (isAcknowledgingFailing) {
                Result.Error(NetworkError.NO_INTERNET)
            } else {
                acknowledged.getOrPut(groupUuid) { mutableListOf() } += messageUuids
                waiting[groupUuid]?.removeAll { it.uuid in messageUuids }
                Result.Success(Unit)
            }
        },
        leave = { groupUuid ->
            left += groupUuid

            // As the server takes it: Bob is no current member any more, and what waited for him
            // there is dropped, with his copy of its key.
            serverMembers -= BOB
            waiting.remove(groupUuid)
            server.copies.remove(groupUuid)

            GroupLeaveAnswer.Left
        },
        isUnblockedContact = { it in contacts },
        client = { Result.Success(bob) },
        save = { group, groupMembers, olderKeys, groupSignatureKeys ->
            groups[group.uuid] = group
            members[group.uuid] = groupMembers
            hold(olderKeys + group.currentKey())
            record(groupSignatureKeys)
        },
        memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        recordSignatureKeys = ::record,
        takeKeys = { groupUuid, keys ->
            hold(keys)
            groups[groupUuid] = groups.getValue(groupUuid).with(
                groupKey = keys.last().groupKey,
                keyGeneration = keys.last().generation,
                isBackedUp = false,
            )
        },
        updateInformation = { _, _ -> error("Alice edits nothing here.") },
        markLeft = { groupUuid -> groups[groupUuid] = groups.getValue(groupUuid).with(isLeft = true) },
        recordDeclined = { groupUuid -> declined += groupUuid },
        forgetDeclined = { groupUuid -> declined -= groupUuid },
        removeDeparted = { _, _ -> error("Bob created no group here.") },
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
        handOutProfileKey = {
            onHandOut()
            Result.Success(Unit)
        },
        rebuildWakeList = {
            steps += "wake list"
            wakeListRebuilds += members.toMap()
        },
        resendScheduler = ResendsBooked(),
    )

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
        bob.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())
    }

    /**
     * Every key a group handed over in the pass has its copy put up by the pass, and who may wake
     * Bob is rebuilt last of all, whatever failed.
     */
    @Test
    fun `the membership changes still pending are sent again before anything and the key copies put up last`() =
        runBlocking<Unit> {
            membershipResend = Result.Error(GroupSyncError.Network(NetworkError.SERVER_ERROR))

            val pass = groupSync.sync(contactsTakenIn = true)

            assertEquals(listOf("creations", "membership changes", "key copies", "wake list"), steps)
            assertEquals(GroupSyncError.Network(NetworkError.SERVER_ERROR), pass.failure)
        }

    @Test
    fun `a member the creator adds is recorded once the Commit is read`() = runBlocking<Unit> {
        joined()

        val commit = aliceAdds(dave)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertTrue(pass.removed.isEmpty())
        assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE), members.getValue(GROUP).toSet())
        assertEquals(listOf(commit), acknowledged[GROUP]?.toList())
        assertEquals(bobRoster().epoch, alice.rosterOf(mlsGroupId = aliceGroup()).epoch)
    }

    /**
     * As [GroupMembershipSender] has her: the removal, then the new key in the epoch after it. The
     * server lists the copy of the key it replaced, which the new one's replaces.
     */
    @Test
    fun `a member the creator removes goes and the key she hands over next is taken and its copy put up`() =
        runBlocking<Unit> {
            joined()

            val removal = aliceRemoves(CAROL)
            val rotation = aliceRotates()

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.removed.isEmpty())
            assertEquals(rowsOf(ALICE, BOB), members.getValue(GROUP).toSet())
            assertEquals(rowsOf(ALICE, BOB), wakeListRebuilds.last().getValue(GROUP).toSet())

            val group = groups.getValue(GROUP)

            assertContentEquals(groupKey, group.groupKey)
            assertContentEquals(groupKey, server.keyOf(GROUP))
            assertTrue(group.isBackedUp)
            assertEquals(listOf(removal, rotation), acknowledged[GROUP]?.toList())
        }

    /**
     * Two rotations read in one pass, as for a member whose device was off for both: what was sealed
     * under each stays readable with it, every generation kept, the last one the group's.
     */
    @Test
    fun `every key the creator hands over is kept as its generation the last one the group's`() =
        runBlocking<Unit> {
            joined()

            val first = groupKey

            aliceRemoves(CAROL)
            aliceRotates()

            val second = groupKey

            aliceRotates()

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val group = groups.getValue(GROUP)

            assertEquals(3L, group.keyGeneration)
            assertContentEquals(groupKey, group.groupKey)
            assertEquals(setOf(1L, 2L, 3L), heldKeys.getValue(GROUP).keys)
            assertContentEquals(first, heldKeys.getValue(GROUP)[1])
            assertContentEquals(second, heldKeys.getValue(GROUP)[2])
            assertEquals(3L, server.generationOf(GROUP))
        }

    /**
     * What the members write is checked against it, see `GroupMemberSignatureKeyEntity`: what
     * Carol wrote while in the group stays in it.
     */
    @Test
    fun `the signature key each member holds in the tree is recorded and kept once they go`() =
        runBlocking<Unit> {
            joined()

            assertEquals(setOf(ALICE, BOB, CAROL), signatureKeys.getValue(GROUP).keys)
            assertContentEquals(carol.signaturePublicKey(), signatureKeys.getValue(GROUP)[CAROL])

            aliceRemoves(CAROL)
            aliceAdds(dave)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertEquals(setOf(ALICE, BOB, CAROL, DAVE), signatureKeys.getValue(GROUP).keys)
            assertContentEquals(dave.signaturePublicKey(), signatureKeys.getValue(GROUP)[DAVE])
        }

    /** Nothing reaches a member once removed, and nothing in the chain leads forward, see [GroupKeyChain]. */
    @Test
    fun `the user removed never holds the generation after the one they were removed at`() = runBlocking<Unit> {
        joined()
        aliceRemoves(BOB)
        aliceRotates()

        groupSync.sync(contactsTakenIn = true)

        val group = groups.getValue(GROUP)

        assertTrue(group.isLeft)
        assertEquals(FIRST_KEY_GENERATION, group.keyGeneration)
        assertEquals(setOf(FIRST_KEY_GENERATION), heldKeys.getValue(GROUP).keys)
        assertEquals(Result.Error(GroupKeyChainError.NotHeld), bobsKeyChain().keyOf(groupUuid = GROUP, generation = 2))
    }

    /**
     * Removed at the first generation and added back at the third: the one in between, which never
     * reached him, comes back through the chain from the key the Welcome hands over.
     */
    @Test
    fun `the user added back after rotations gets the generation they missed back through the chain`() =
        runBlocking<Unit> {
            joined()
            aliceRemoves(BOB)
            groupSync.sync(contactsTakenIn = true)

            aliceRotates()

            val missed = groupKey

            aliceRotates()
            aliceAdds(bob)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val group = groups.getValue(GROUP)

            assertFalse(group.isLeft)
            assertEquals(3L, group.keyGeneration)
            assertEquals(setOf(1L, 3L), heldKeys.getValue(GROUP).keys)

            val recovered = bobsKeyChain().keyOf(groupUuid = GROUP, generation = 2)

            assertContentEquals(missed, assertIs<Result.Success<ByteArray>>(recovered).data)
            assertEquals(setOf(1L, 2L, 3L), heldKeys.getValue(GROUP).keys)
        }

    @Test
    fun `the creator removing the user leaves the group read-only and is reported`() = runBlocking<Unit> {
        joined()

        val mlsGroupId = assertNotNull(groups.getValue(GROUP).mlsGroupId)
        val removal = aliceRemoves(BOB)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertEquals(
            listOf(GroupRemoval(groupUuid = GROUP, title = "Ski trip", removerUuid = ALICE, removerFullName = "Alice")),
            pass.removed,
        )

        val group = groups.getValue(GROUP)

        assertTrue(group.isLeft)
        assertContentEquals(mlsGroupId, group.mlsGroupId)
        assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
        assertFalse(bob.holdsGroup(mlsGroupId = mlsGroupId))
        assertEquals(listOf(removal), acknowledged[GROUP]?.toList())
    }

    /**
     * The device locking once the Commit is read, as the user's key is posted say: the removal was
     * recorded before, and the next pass finds the group left, with nothing to tell.
     */
    @Test
    fun `the creator removing the user is reported even when the device locks later in the sync`() =
        runBlocking<Unit> {
            joined()
            aliceRemoves(BOB)

            onHandOut = { error("Cannot access Keystore key - device is locked") }

            val pass = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.DeviceLocked, pass.failure)
            assertEquals(listOf(GROUP), pass.removed.map { it.groupUuid })
            assertTrue(groups.getValue(GROUP).isLeft)
        }

    /** The server lists a group to its current members alone, and the Commit removing Bob is its last word to him. */
    @Test
    fun `a group the user was removed from is neither drained nor reported again`() = runBlocking<Unit> {
        joined()
        aliceRemoves(BOB)
        groupSync.sync(contactsTakenIn = true)
        fetched.clear()

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertTrue(pass.removed.isEmpty())
        assertTrue(fetched.isEmpty())
    }

    /**
     * Alice removes Carol, hands Bob the new key, and removes him too, all before his sync, which
     * reads them at once: putting that key's copy up reads the key ring, which throws once the
     * device locks, and would lose the removal, read once only. Nor does the server take a copy
     * from a member it removed.
     */
    @Test
    fun `a removal read behind a key handed over is recorded without the key's copy being put up`() =
        runBlocking<Unit> {
            joined()

            val carolsRemoval = aliceRemoves(CAROL)
            val rotation = aliceRotates()
            val removal = aliceRemoves(BOB)

            server.isDeviceLocked = true

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.removed.map { it.groupUuid })
            assertTrue(groups.getValue(GROUP).isLeft)
            assertEquals(listOf(carolsRemoval, rotation, removal), acknowledged[GROUP]?.toList())

            fetched.clear()

            val next = groupSync.sync(contactsTakenIn = true)

            assertNull(next.failure)
            assertTrue(next.removed.isEmpty())
            assertTrue(fetched.isEmpty())
        }

    /**
     * The device locking once the key message is read, as the pass reads the key ring to put that
     * key's copy up, the one thing in it that does here: the key was taken and the message
     * acknowledged before, the message being read once only, and the next pass puts the copy up.
     */
    @Test
    fun `a key handed over is taken even when the device locks as its copy is put up`() =
        runBlocking<Unit> {
            joined()

            val removal = aliceRemoves(CAROL)
            val rotation = aliceRotates()

            server.isDeviceLocked = true

            val pass = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.DeviceLocked, pass.failure)
            assertEquals(listOf(removal, rotation), acknowledged[GROUP]?.toList())
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
            assertFalse(groups.getValue(GROUP).isBackedUp)
            assertFalse(groupKey contentEquals server.keyOf(GROUP))

            server.isDeviceLocked = false

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertContentEquals(groupKey, server.keyOf(GROUP))
            assertTrue(groups.getValue(GROUP).isBackedUp)
        }

    /**
     * Bob's storage fails as the sync deletes the group Alice removed him from, which leaves its
     * state held: the Welcome adding him back would be refused for a group he holds still.
     */
    @Test
    fun `the user added back after a removal whose deletion failed joins again once the group is deleted`() =
        runBlocking<Unit> {
            joined()

            val mlsGroupId = bobGroup()

            aliceRemoves(BOB)

            bobStore.failing = setOf(MlsNamespace.METADATA)

            val removedPass = groupSync.sync(contactsTakenIn = true)

            assertNull(removedPass.failure)
            assertEquals(listOf(GROUP), removedPass.removed.map { it.groupUuid })
            assertTrue(groups.getValue(GROUP).isLeft)
            assertTrue(bob.holdsGroup(mlsGroupId = mlsGroupId))

            aliceRotates()
            acknowledged.clear()

            val welcome = aliceAdds(bob)

            val failed = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Storage, failed.failure)
            assertTrue(failed.joined.isEmpty())
            assertNull(acknowledged[GROUP])

            bobStore.failing = emptySet()

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })

            val group = groups.getValue(GROUP)

            assertFalse(group.isLeft)
            assertContentEquals(groupKey, group.groupKey)
            assertTrue(bob.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))
            assertEquals(listOf(welcome), acknowledged[GROUP]?.toList())
        }

    @Test
    fun `a Commit from a member other than the creator is acknowledged and changes nothing`() = runBlocking<Unit> {
        joined()

        val carolGroupId = carolJoins()
        val forged = carol.addMembersTo(
            mlsGroupId = carolGroupId,
            invitees = listOf(mallory.inviteeKeyPackage()),
            welcomePayload = groupKey,
        ).commit
        val forgedUuid = post(sender = CAROL, ciphertext = forged)

        val epoch = bobRoster().epoch
        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(forgedUuid), acknowledged[GROUP]?.toList())
        assertEquals(epoch, bobRoster().epoch)
        assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())

        // Refused for who committed it, storing nothing: refused the same way still.
        assertFailsWith<MlsException.UnauthorizedChange> {
            bob.processGroupMessageIn(mlsGroupId = bobGroup(), message = forged, creatorUuid = ALICE)
        }

        // And the creator's next change is read as if it had never come.
        aliceAdds(dave)
        groupSync.sync(contactsTakenIn = true)

        assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE), members.getValue(GROUP).toSet())
    }

    /** Kept, it would stop the group sending until somebody committed it, and be committed with Alice's next change. */
    @Test
    fun `a Proposal is acknowledged and changes nothing`() = runBlocking<Unit> {
        joined()

        val carolGroupId = carolJoins()
        val proposal = carol.proposeSelfRemoveIn(carolGroupId)
        val proposalUuid = post(sender = CAROL, ciphertext = proposal)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(proposalUuid), acknowledged[GROUP]?.toList())

        assertFailsWith<MlsException.UnauthorizedChange> {
            bob.processGroupMessageIn(mlsGroupId = bobGroup(), message = proposal, creatorUuid = ALICE)
        }

        val rotation = aliceRotates()

        groupSync.sync(contactsTakenIn = true)

        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        assertEquals(listOf(proposalUuid, rotation), acknowledged[GROUP]?.toList())
    }

    /** Any member can send an application message: only the creator's hands over the group's key. */
    @Test
    fun `a key handed over by a member other than the creator is acknowledged and not taken`() =
        runBlocking<Unit> {
            joined()

            val carolGroupId = carolJoins()
            val handedOver = carol.encryptApplicationMessage(
                mlsGroupId = carolGroupId,
                payload = groupKeyMessageOf(groupKey = generateSymmetricEncryptionKey(), keyGeneration = 2),
            )
            val messageUuid = post(sender = CAROL, ciphertext = handedOver)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
            assertContentEquals(groupKey, server.keyOf(GROUP))
            assertEquals(listOf(messageUuid), acknowledged[GROUP]?.toList())
        }

    @Test
    fun `an application message of the creator handing over no key is acknowledged and ignored`() =
        runBlocking<Unit> {
            joined()

            val message = alice.encryptApplicationMessage(mlsGroupId = aliceGroup(), payload = "hello".encodeToByteArray())
            val messageUuid = post(sender = ALICE, ciphertext = message)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
            assertEquals(listOf(messageUuid), acknowledged[GROUP]?.toList())
        }

    /** Whatever its sender posted: the server stores a message without reading it. */
    @Test
    fun `a message that does not read is acknowledged and the next one read`() = runBlocking<Unit> {
        joined()

        val garbage = post(sender = CAROL, ciphertext = ByteArray(64) { 5 })
        val notBase64 = post(sender = CAROL, ciphertext = null)
        val commit = aliceAdds(dave)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(garbage, notBase64, commit), acknowledged[GROUP]?.toList())
        assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE), members.getValue(GROUP).toSet())
    }

    /** Storage failing says nothing about the message, which the next sync reads. */
    @Test
    fun `a message whose reading cannot store the group is left waiting with what comes after it`() =
        runBlocking<Unit> {
            joined()

            val commit = aliceAdds(dave)
            val rotation = aliceRotates()

            bobStore.failing = setOf(MlsNamespace.GROUP_STATE)

            val failed = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Storage, failed.failure)
            assertNull(acknowledged[GROUP])
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())

            bobStore.failing = emptySet()

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(commit, rotation), acknowledged[GROUP]?.toList())
            assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE), members.getValue(GROUP).toSet())
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        }

    /** The same, the message left waiting on the first page and what comes after it on the next. */
    @Test
    fun `a message left waiting on one page leaves what the next pages list unacknowledged`() =
        runBlocking<Unit> {
            joined()
            messagesPerPage = 1

            val commit = aliceAdds(dave)
            val rotation = aliceRotates()

            bobStore.failing = setOf(MlsNamespace.GROUP_STATE)

            assertEquals(GroupSyncError.Storage, groupSync.sync(contactsTakenIn = true).failure)
            assertNull(acknowledged[GROUP])

            bobStore.failing = emptySet()

            assertNull(groupSync.sync(contactsTakenIn = true).failure)
            assertEquals(listOf(commit, rotation), acknowledged[GROUP]?.toList())
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        }

    /**
     * Somebody holding Dave's UUID under a key of their own is who the server served Alice as him,
     * against the one Bob pinned for Dave: taken in, the Commit would have Bob trust them.
     */
    @Test
    fun `a Commit seating a member pinned to another identity is left waiting and reported`() =
        runBlocking<Unit> {
            joined()

            val impostor = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 9 }, store = InMemoryMlsStore())

            aliceAdds(impostor)
            aliceRotates()

            val epoch = bobRoster().epoch
            val pass = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.IdentityChanged, pass.failure)
            assertNull(acknowledged[GROUP])
            assertEquals(epoch, bobRoster().epoch)
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
            assertEquals(2, waiting.getValue(GROUP).size)
        }

    /** Alice removed Carol and rotated the key between adding Bob and his sync: his Welcome's key opens nothing. */
    @Test
    fun `a group joined catches up with a rotation that came after the Welcome`() = runBlocking<Unit> {
        val welcome = aliceCreates()
        val removal = aliceRemoves(CAROL)
        val rotation = aliceRotates()

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(
            listOf(GroupArrival(groupUuid = GROUP, title = "Ski trip", adderUuid = ALICE, adderFullName = "Alice")),
            pass.joined,
        )

        val group = groups.getValue(GROUP)

        assertContentEquals(groupKey, group.groupKey)
        assertContentEquals(groupKey, server.keyOf(GROUP))
        assertEquals(ALICE, group.ownerUuid)
        assertFalse(group.isLeft)
        assertEquals(rowsOf(ALICE, BOB), members.getValue(GROUP).toSet())
        assertEquals(listOf(welcome, removal, rotation), acknowledged[GROUP]?.toList())
        assertEquals(alice.rosterOf(mlsGroupId = aliceGroup()).epoch, bobRoster().epoch)
    }

    /**
     * The server serving fields sealed under none of the keys Alice handed over: the leave drops
     * what was read to tell, which is acknowledged no more than the Welcome.
     */
    @Test
    fun `a group whose fields open under no key its creator handed over is declined and left once caught up`() =
        runBlocking<Unit> {
            aliceCreates()
            aliceRemoves(CAROL)
            aliceRotates()

            serverVersion = FIELDS.sealedAs(
                creator = alice,
                groupKey = generateSymmetricEncryptionKey(),
                groupUuid = GROUP,
            )

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(groups.isEmpty())
            assertEquals(listOf(GROUP), left)
            assertTrue(declined.isEmpty())
            assertNull(acknowledged[GROUP])
            assertTrue(GROUP !in waiting)
            assertTrue(bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty())
        }

    /**
     * The server serving, under the key Alice handed over last, a version she did not sign: nothing
     * of the group is shown on Bob's device yet to keep instead, so it is refused as one that does
     * not open, once the join let him read that key.
     */
    @Test
    fun `a group whose version its creator did not sign is declined and left once caught up`() =
        runBlocking<Unit> {
            aliceCreates()
            aliceRemoves(CAROL)
            aliceRotates()

            serverVersion = FIELDS.sealedAs(creator = carol, groupKey = groupKey, groupUuid = GROUP)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(groups.isEmpty())
            assertEquals(listOf(GROUP), left)
            assertTrue(bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty())
        }

    @Test
    fun `the user added back after a removal joins again into the group they were removed from`() =
        runBlocking<Unit> {
            joined()
            aliceRemoves(BOB)
            groupSync.sync(contactsTakenIn = true)

            // The key goes to Carol alone: Bob is no longer a member.
            aliceRotates()
            acknowledged.clear()

            val welcome = aliceAdds(bob)

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
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
            assertEquals(listOf(welcome), acknowledged[GROUP]?.toList())

            // In the epoch Alice is in: what she hands over next reaches him.
            aliceRotates()
            groupSync.sync(contactsTakenIn = true)

            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        }

    @Test
    fun `the user removed and added back before a sync is in the group with only the addition reported`() =
        runBlocking<Unit> {
            joined()
            aliceRemoves(BOB)
            aliceRotates()
            aliceAdds(bob)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertTrue(pass.removed.isEmpty())
            assertFalse(groups.getValue(GROUP).isLeft)
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
            assertTrue(waiting.getValue(GROUP).isEmpty())
        }

    /**
     * Alice adds Bob, removes him and adds him back before he syncs, rotating the key in between: the
     * first Welcome, whose key opens nothing the server lists any more, says who the creator is,
     * and Bob joins from the last one she wrote, where the group stands now.
     */
    @Test
    fun `a group the user was added to and removed from and added back to before a sync is joined from the last Welcome`() =
        runBlocking<Unit> {
            val firstWelcome = aliceCreates()
            val removal = aliceRemoves(BOB)

            aliceRotates()

            val lastWelcome = aliceAdds(bob)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertTrue(pass.removed.isEmpty())

            val group = groups.getValue(GROUP)

            assertFalse(group.isLeft)
            assertContentEquals(groupKey, group.groupKey)
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
            assertEquals(listOf(firstWelcome, removal, lastWelcome), acknowledged[GROUP]?.toList())
            assertEquals(alice.rosterOf(mlsGroupId = aliceGroup()).epoch, bobRoster().epoch)
        }

    /**
     * The same, the server listing a message a page: the Welcome Bob is joined from is on the
     * last page, and the Welcome on the first still says who the creator is.
     */
    @Test
    fun `the last Welcome its creator wrote is joined from whichever page it is listed on`() =
        runBlocking<Unit> {
            messagesPerPage = 1

            val firstWelcome = aliceCreates()
            val removal = aliceRemoves(BOB)

            aliceRotates()

            val lastWelcome = aliceAdds(bob)

            assertEquals(listOf(firstWelcome, removal, lastWelcome), waiting.getValue(GROUP).map { it.uuid })

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertTrue(pass.removed.isEmpty())
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
            assertEquals(listOf(firstWelcome, removal, lastWelcome), acknowledged[GROUP]?.toList())
            assertEquals(alice.rosterOf(mlsGroupId = aliceGroup()).epoch, bobRoster().epoch)
        }

    /** Listed as he was still in it, the Commit removing him reaching his device behind the Welcome. */
    @Test
    fun `a group the user is removed from as they join it is kept as one they were removed from`() =
        runBlocking<Unit> {
            val welcome = aliceCreates()
            val removal = aliceRemoves(BOB)

            isListedRegardless = true

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertEquals(
                listOf(GroupRemoval(groupUuid = GROUP, title = "Ski trip", removerUuid = ALICE, removerFullName = "Alice")),
                pass.removed,
            )

            val group = groups.getValue(GROUP)

            assertTrue(group.isLeft)
            assertNotNull(group.mlsGroupId)
            assertTrue(server.putUp.isEmpty())
            assertTrue(bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty())
            assertEquals(listOf(welcome, removal), acknowledged[GROUP]?.toList())
        }

    /**
     * Putting the key's copy up reads the key ring, which throws once the device locks, and would
     * lose the removal.
     */
    @Test
    fun `a group the user is removed from as they join it is reported on a locked device`() =
        runBlocking<Unit> {
            val welcome = aliceCreates()
            val removal = aliceRemoves(BOB)

            isListedRegardless = true
            server.isDeviceLocked = true

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.removed.map { it.groupUuid })
            assertTrue(groups.getValue(GROUP).isLeft)
            assertEquals(listOf(welcome, removal), acknowledged[GROUP]?.toList())
        }

    /** As for a group the user was never in: the creator is to be a contact this device pinned. */
    @Test
    fun `a group the user was removed from is not joined again from a creator no longer a contact`() =
        runBlocking<Unit> {
            joined()
            aliceRemoves(BOB)
            groupSync.sync(contactsTakenIn = true)
            acknowledged.clear()

            contacts = setOf(DAVE)

            val welcome = aliceAdds(bob)

            val waited = groupSync.sync(contactsTakenIn = false)

            assertNull(waited.failure)
            assertNull(acknowledged[GROUP])

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(groups.getValue(GROUP).isLeft)
            assertTrue(declined.isEmpty())
            assertTrue(left.isEmpty())
            assertEquals(listOf(welcome), acknowledged[GROUP]?.toList())
            assertTrue(bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty())
        }

    /**
     * The acknowledgement of Alice's removal of Bob is lost, so the Commit still waits when she adds
     * him back, ahead of the Welcome.
     */
    @Test
    fun `the Welcome adding the user back is joined from behind what was left unacknowledged`() =
        runBlocking<Unit> {
            joined()

            val removal = aliceRemoves(BOB)

            isAcknowledgingFailing = true

            val failed = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Network(NetworkError.NO_INTERNET), failed.failure)
            assertEquals(listOf(GROUP), failed.removed.map { it.groupUuid })

            isAcknowledgingFailing = false

            val welcome = aliceAdds(bob)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertFalse(groups.getValue(GROUP).isLeft)
            assertEquals(listOf(removal, welcome), acknowledged[GROUP]?.toList())
        }

    /**
     * Dave, a contact Bob pinned, creates a group of his own under the UUID of Alice's, whose id is
     * hers, and the server lists hers to Bob again with Dave's Welcome waiting, at an epoch past the
     * one Bob deleted it at: it is Alice's alone to add him back.
     */
    @Test
    fun `a Welcome back into a group the user was removed from is refused from anybody but its creator`() =
        runBlocking<Unit> {
            joined()
            aliceRemoves(BOB)
            groupSync.sync(contactsTakenIn = true)
            acknowledged.clear()

            val davesGroupId = dave.createGroup(
                groupUuid = GROUP,
                invitees = listOf(carol.inviteeKeyPackage()),
                welcomePayload = groupKey,
            ).mlsGroupId
            val forged = dave.addMembersTo(
                mlsGroupId = davesGroupId,
                invitees = listOf(bob.inviteeKeyPackage()),
                welcomePayload = groupKey,
            ).welcome

            // What the library would take it for: a way back in, but for who wrote it.
            assertEquals(DAVE, bob.inspectWelcome(welcome = forged, expectedGroupUuid = GROUP).committerUserUuid)

            isListedRegardless = true
            val forgedUuid = post(sender = DAVE, ciphertext = forged)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(groups.getValue(GROUP).isLeft)
            assertTrue(declined.isEmpty())
            assertTrue(left.isEmpty())
            assertEquals(listOf(forgedUuid), acknowledged[GROUP]?.toList())
            assertTrue(bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty())
        }

    @Test
    fun `a Welcome back into a group the user was removed from holds pruning back until it is joined`() =
        runBlocking<Unit> {
            joined()
            aliceRemoves(BOB)
            groupSync.sync(contactsTakenIn = true)

            assertTrue(groupSync.hasNoWelcomeWaiting())

            aliceAdds(bob)

            assertFalse(groupSync.hasNoWelcomeWaiting())

            groupSync.sync(contactsTakenIn = true)

            assertTrue(groupSync.hasNoWelcomeWaiting())
        }

    /** As a log-out leaves Bob: the copy of the key listed, no MLS state to read anything with. */
    @Test
    fun `a group brought back after a log-out takes the server's members and acknowledges what waits unread`() =
        runBlocking<Unit> {
            broughtBack()

            val commit = aliceAdds(dave)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(commit), acknowledged[GROUP]?.toList())
            assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE), members.getValue(GROUP).toSet())
            assertTrue(bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty())
            assertTrue(declined.isEmpty())
            assertTrue(left.isEmpty())
        }

    /**
     * The server lists a group to its current members alone, and Bob holds no MLS state to read the
     * Commit with, which waits for the Welcome adding him back to be acknowledged with.
     */
    @Test
    fun `a group brought back after a log-out that the user is removed from is left and reported`() =
        runBlocking<Unit> {
            broughtBack()

            aliceRemoves(BOB)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(
                listOf(GroupRemoval(groupUuid = GROUP, title = "Ski trip", removerUuid = ALICE, removerFullName = "Alice")),
                pass.removed,
            )
            assertTrue(groups.getValue(GROUP).isLeft)
            assertNull(acknowledged[GROUP])

            assertTrue(groupSync.sync(contactsTakenIn = true).removed.isEmpty())
        }

    @Test
    fun `a group brought back after a log-out is joined again once its creator removes the user and adds them back`() =
        runBlocking<Unit> {
            broughtBack()

            val removal = aliceRemoves(BOB)

            groupSync.sync(contactsTakenIn = true)
            aliceRotates()

            val welcome = aliceAdds(bob)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertTrue(pass.removed.isEmpty())

            val group = groups.getValue(GROUP)

            assertFalse(group.isLeft)
            assertContentEquals(groupKey, group.groupKey)
            assertContentEquals(groupKey, server.keyOf(GROUP))
            assertTrue(bob.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))
            assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP).toSet())
            assertEquals(listOf(removal, welcome), acknowledged[GROUP]?.toList())

            // In the epoch Alice is in: what she hands over next reaches him.
            aliceRotates()
            groupSync.sync(contactsTakenIn = true)

            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        }

    /**
     * Alice removes Bob and adds him back at once, the Welcome landing once his sync, which the
     * removal woke, listed his groups without this one: what waits there is left for the next sync,
     * which joins from that Welcome.
     */
    @Test
    fun `a group brought back after a log-out is joined again from a Welcome landing as the sync leaves it`() =
        runBlocking<Unit> {
            broughtBack()

            val removal = aliceRemoves(BOB)

            aliceRotates()

            var welcome: String? = null

            afterListing = { welcome = aliceAdds(bob) }

            val left = groupSync.sync(contactsTakenIn = true)

            assertNull(left.failure)
            assertEquals(listOf(GROUP), left.removed.map { it.groupUuid })
            assertNull(acknowledged[GROUP])

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })

            val group = groups.getValue(GROUP)

            assertFalse(group.isLeft)
            assertNotNull(group.mlsGroupId)
            assertEquals(listOf(removal, assertNotNull(welcome)), acknowledged[GROUP]?.toList())
        }

    /** Listed all along, the server taking Bob out and back in between two of his syncs. */
    @Test
    fun `a group brought back after a log-out is joined again from a removal and an addition read in one sync`() =
        runBlocking<Unit> {
            broughtBack()

            val removal = aliceRemoves(BOB)

            aliceRotates()

            val welcome = aliceAdds(bob)

            assertFalse(groupSync.hasNoWelcomeWaiting())

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertTrue(pass.removed.isEmpty())

            val group = groups.getValue(GROUP)

            assertFalse(group.isLeft)
            assertNotNull(group.mlsGroupId)
            assertContentEquals(groupKey, group.groupKey)
            assertEquals(listOf(removal, welcome), acknowledged[GROUP]?.toList())
            assertTrue(groupSync.hasNoWelcomeWaiting())
        }

    /**
     * As a log-out leaves Bob while Alice removes Carol: the key his copy holds is the one the
     * fields were sealed under before she rotated it, in a message he can never read.
     */
    @Test
    fun `a group whose copied key was rotated since is not brought back and what waits is acknowledged`() =
        runBlocking<Unit> {
            loggedOut()

            val removal = aliceRemoves(CAROL)
            val rotation = aliceRotates()

            assertFalse(groupSync.hasNoWelcomeWaiting())

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(groups.isEmpty())
            assertTrue(declined.isEmpty())
            assertTrue(left.isEmpty())
            assertEquals(listOf(removal, rotation), acknowledged[GROUP]?.toList())
            assertTrue(groupSync.hasNoWelcomeWaiting())
        }

    /**
     * The server dropped Bob's copy as Alice removed him, so nothing tells his device the group was
     * his: the Commit removing him waits before the Welcome adding him back, unread.
     */
    @Test
    fun `a group whose copied key was rotated since is joined once its creator adds the user back`() =
        runBlocking<Unit> {
            loggedOut()
            aliceRemoves(CAROL)
            aliceRotates()
            groupSync.sync(contactsTakenIn = true)
            acknowledged.clear()

            val removal = aliceRemoves(BOB)

            aliceRotates()

            val welcome = aliceAdds(bob)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(
                listOf(GroupArrival(groupUuid = GROUP, title = "Ski trip", adderUuid = ALICE, adderFullName = "Alice")),
                pass.joined,
            )

            val group = groups.getValue(GROUP)

            assertFalse(group.isLeft)
            assertEquals(ALICE, group.ownerUuid)
            assertContentEquals(groupKey, group.groupKey)
            assertContentEquals(groupKey, server.keyOf(GROUP))
            assertTrue(bob.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))
            assertEquals(rowsOf(ALICE, BOB), members.getValue(GROUP).toSet())
            assertEquals(listOf(removal, welcome), acknowledged[GROUP]?.toList())
        }

    /**
     * Has Alice create [GROUP] with Bob and Carol, and Bob hold it as a log-out leaves him once the
     * server kept his copy of its key: no MLS state, and the Welcome long acknowledged.
     */
    private fun loggedOut() {
        aliceCreates()

        server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP)
        waiting.getValue(GROUP).clear()
    }

    /** Has Bob hold the group [loggedOut] leaves him with as the sync brings it back, see [GroupSync]. */
    private fun broughtBack() {
        loggedOut()

        groups[GROUP] = GroupEntity(
            uuid = GROUP,
            title = FIELDS.title,
            description = FIELDS.description,
            currency = FIELDS.currency,
            infoRevision = FIRST_GROUP_REVISION,
            ownerUuid = ALICE,
            mlsGroupId = null,
            groupKey = groupKey,
            keyGeneration = FIRST_KEY_GENERATION,
            isBackedUp = true,
            isLeft = false,
            handedProfileKeyHash = null,
        )
        members[GROUP] = rowsOf(ALICE, BOB, CAROL).toList()
    }

    /** Has the sync join Alice's group from its Welcome, as [GroupSyncTest] has it, and forgets what it fetched. */
    private suspend fun joined() {
        aliceCreates()

        assertEquals(listOf(GROUP), groupSync.sync(contactsTakenIn = true).joined.map { it.groupUuid })

        fetched.clear()
        acknowledged.clear()
    }

    /**
     * Has Alice create [GROUP] with Bob and Carol, handing them [groupKey], and the server list it
     * with the Welcome waiting for Bob, and returns the Welcome's UUID.
     */
    private fun aliceCreates(): String {
        val creation = alice.createGroup(
            groupUuid = GROUP,
            invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(
                groupKey = groupKey,
                keyGeneration = keyGeneration,
                profileKeys = emptyMap(),
            ),
        )

        aliceGroupId = creation.mlsGroupId
        carolsWelcome = creation.welcome
        serverMembers += listOf(ALICE, BOB, CAROL)
        serverVersion = FIELDS.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP)

        return post(sender = ALICE, ciphertext = creation.welcome)
    }

    /**
     * Has Alice add [invitee], and the server deliver the Commit to the members already in the group
     * but her, and the Welcome to [invitee]: returns the UUID of whichever reached Bob.
     */
    private suspend fun aliceAdds(invitee: MlsClient): String {
        val addition = alice.addMembersTo(
            mlsGroupId = aliceGroup(),
            invitees = listOf(invitee.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(
                groupKey = groupKey,
                keyGeneration = keyGeneration,
                profileKeys = emptyMap(),
            ),
        )

        alice.applyPendingCommitIn(mlsGroupId = aliceGroup())

        val delivered = deliver(addition.commit)

        serverMembers += invitee.userUuid()

        return if (invitee == bob) post(sender = ALICE, ciphertext = addition.welcome) else assertNotNull(delivered)
    }

    /**
     * Has Alice remove [userUuid], and the server deliver the Commit to every current member but her,
     * [userUuid] included, then take them off the group, dropping Bob's copy of its key with him:
     * returns the UUID of the Commit Bob got.
     */
    private suspend fun aliceRemoves(userUuid: String): String {
        val commit = alice.removeMembersFrom(mlsGroupId = aliceGroup(), userUuids = listOf(userUuid))

        alice.applyPendingCommitIn(mlsGroupId = aliceGroup())

        val delivered = assertNotNull(deliver(commit))

        serverMembers -= userUuid

        if (userUuid == BOB) server.copies.remove(GROUP)

        return delivered
    }

    /**
     * Has Alice hand the members a new key, of the next generation, as [GroupMembershipSender] does
     * once a removal is applied, and the server re-seal the group's fields under it and keep the
     * link back to the key it replaces, and returns the UUID of the key message, or of nothing when
     * it was not Bob's to get.
     */
    private suspend fun aliceRotates(): String {
        val newKey = generateSymmetricEncryptionKey()
        val newGeneration = keyGeneration + 1
        val message = alice.encryptApplicationMessage(
            mlsGroupId = aliceGroup(),
            payload = groupKeyMessageOf(groupKey = newKey, keyGeneration = newGeneration),
        )

        val delivered = deliver(message)

        serverLinks += GroupKeyLinkDto(
            generation = newGeneration,
            previousKey = assertNotNull(
                sealKeyLink(previousKey = groupKey, groupKey = newKey, groupUuid = GROUP, generation = newGeneration),
            ),
        )
        groupKey = newKey
        keyGeneration = newGeneration
        serverVersion = FIELDS.sealedAs(creator = alice, groupKey = newKey, groupUuid = GROUP)

        return delivered ?: ""
    }

    /** Has the server hand [message] from Alice to Bob, provided he is a current member, and returns its UUID then. */
    private fun deliver(message: ByteArray): String? =
        if (BOB in serverMembers) post(sender = ALICE, ciphertext = message) else null

    /**
     * Has the server put [ciphertext], Base64-encoded, or something that is not Base64 when null,
     * in Bob's queue for [groupUuid], as sent by [sender], and returns its UUID.
     */
    private fun post(sender: String, ciphertext: ByteArray?, groupUuid: String = GROUP): String {
        val uuid = "m${++messageCount}"

        waiting.getOrPut(groupUuid) { mutableListOf() } += GroupMessageDto(
            uuid = uuid,
            senderUserUuid = sender,
            ciphertext = ciphertext?.let { Base64.Default.encode(it) } ?: "not Base64!",
            createdAt = "2026-10-26T12:00:00Z",
        )

        return uuid
    }

    /** The group as the server lists it to Bob: to its current members alone, unless a test says otherwise. */
    private fun listedGroup(): GroupDto? {
        val version = serverVersion ?: return null

        if (BOB !in serverMembers && !isListedRegardless) return null

        return listedGroupOf(
            uuid = GROUP,
            ownerUuid = ALICE,
            version = version,
            memberUuids = serverMembers.toList(),
            keyCopy = server.copies[GROUP],
            keyCopyGeneration = server.listedGenerationOf(GROUP),
            keyGeneration = keyGeneration,
        )
    }

    /** Records [keys] among the generations Bob's device holds, as `GroupDao.upsertKeys` does. */
    private fun hold(keys: List<GroupKeyEntity>) {
        keys.forEach { heldKeys.getOrPut(it.groupUuid) { mutableMapOf() }[it.generation] = it.groupKey }
    }

    /** Records [recorded] as the members' signature keys, as `GroupDao.upsertSignatureKeys` does. */
    private fun record(recorded: List<GroupMemberSignatureKeyEntity>) {
        recorded.forEach { signatureKeys.getOrPut(it.groupUuid) { mutableMapOf() }[it.userUuid] = it.signatureKey }
    }

    /**
     * What `GroupKeyChain` gives of the keys of [GROUP] on Bob's device, from what it holds and the
     * links the server lists to its current members, as `GroupDao` keeps them.
     */
    private fun bobsKeyChain() = GroupKeyChain(
        keyLinks = {
            if (BOB in serverMembers) Result.Success(serverLinks.toList()) else Result.Error(NetworkError.NOT_FOUND)
        },
        group = { groups[it] },
        heldKeys = { groupUuid ->
            heldKeys[groupUuid].orEmpty().map { (generation, key) ->
                GroupKeyEntity(groupUuid = groupUuid, generation = generation, groupKey = key)
            }
        },
        storeRecovered = { groupUuid, keys ->
            keys.forEach { heldKeys.getValue(groupUuid).getOrPut(it.generation) { it.groupKey } }
        },
    )

    /** Has Carol join Alice's group from its Welcome, and returns her copy of it. */
    private fun carolJoins(): ByteArray = carol.joinGroup(
        welcome = assertNotNull(carolsWelcome),
        expectedGroupUuid = GROUP,
        expectedMembers = listOf(ALICE, BOB),
        pinMembers = false,
    )

    private suspend fun bobRoster() = bob.rosterOf(mlsGroupId = bobGroup())

    private fun bobGroup() = assertNotNull(groups[GROUP]?.mlsGroupId)

    private fun aliceGroup() = assertNotNull(aliceGroupId)

    /** Has Carol propose to leave the group [mlsGroupId], as a departing member's device would. */
    private fun MlsClient.proposeSelfRemoveIn(mlsGroupId: ByteArray): ByteArray =
        loadGroup(mlsGroupId = mlsGroupId).use { it.proposeSelfRemove() }
}

private fun rowsOf(vararg userUuids: String) =
    userUuids.mapTo(HashSet()) { GroupMemberEntity(userUuid = it, groupUuid = GROUP, isAdmin = it == ALICE) }

/** The key this group holds as the generation it is, as `GroupDao` keeps it with the group. */
private fun GroupEntity.currentKey() = GroupKeyEntity(groupUuid = uuid, generation = keyGeneration, groupKey = groupKey)

private fun GroupEntity.with(
    groupKey: ByteArray = this.groupKey,
    keyGeneration: Long = this.keyGeneration,
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
    keyGeneration = keyGeneration,
    isBackedUp = isBackedUp,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
