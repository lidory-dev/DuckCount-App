package group

import contact.ProfileKeyUpdate
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.encryptApplicationMessage
import mls.inviteeKeyPackage
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
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
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** What every group here is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What Alice's group sync makes of the group she created with Bob, Carol and Dave once the server
 * no longer lists some of them: members who left it, as a device declining it does, see
 * [GroupApi.leave], and accounts deleted since, which the server drops from the group alike. The
 * removal is built and sent by [GroupMembershipChanger] and [GroupMembershipSender] themselves,
 * with every MLS client made through the library, and the database and the server stood in for.
 */
class GroupSyncDepartedMembersTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

    /** The key the group's fields are sealed under before anybody is removed. */
    private val groupKey = generateSymmetricEncryptionKey()

    private val creation = alice.createGroup(
        groupUuid = GROUP,
        invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage(), dave.inviteeKeyPackage()),
        welcomePayload = welcomePayloadOf(groupKey = groupKey, profileKeys = emptyMap()),
    )

    /**
     * Who the server holds as the group's current members, whether it lists the group at all, and
     * its information, signed by Alice.
     */
    private val serverMembers = mutableListOf(ALICE, BOB, CAROL, DAVE)
    private var isListed = true
    private var serverVersion = FIELDS.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP)

    /** The other groups the server lists to Alice, as a test has it. */
    private val otherListings = mutableListOf<GroupDto>()

    private val groups = mutableMapOf(GROUP to createdGroup())
    private val members = mutableMapOf(GROUP to rowsOf(ALICE, BOB, CAROL, DAVE))
    private val pending = mutableMapOf<String, PendingMembershipChangeEntity>()
    private val pendingCreations = mutableSetOf<String>()

    /** What waits for Alice in each group, the groups whose messages cannot be asked for, and what she acknowledged. */
    private val waiting = mutableMapOf<String, MutableList<GroupMessageDto>>()
    private val unreachableMessages = mutableSetOf<String>()
    private val acknowledged = mutableMapOf<String, MutableList<String>>()

    /** The keys to the members' own profiles the sync took. */
    private val ownerKeys = mutableMapOf<String, ByteArray>()

    /** What the server answers a removal and a key rotation, and those that reached it. */
    private var removeAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made
    private var rotateAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made
    private val removals = mutableListOf<RemoveGroupMembersBody>()
    private val rotations = mutableListOf<RotateGroupKeyBody>()

    /** What reached the server, in order. */
    private val steps = mutableListOf<String>()

    private val resends = ResendsBooked()

    /** What seals Alice's copy of each new key a rotation carries, see [ServerKeyCopies]. */
    private val keyCopies = ServerKeyCopies().groupKeyCopies(groups)

    private val sender = GroupMembershipSender(
        addMembers = { _, _ -> error("Alice adds nobody here.") },
        removeMembers = { _, body ->
            steps += "removal"
            removals += body

            removeAnswer
        },
        rotateKey = { _, body ->
            steps += "rotation"
            rotations += body

            if (rotateAnswer == GroupMembershipAnswer.Made) serverVersion = serverVersion.rotatedBy(body)

            rotateAnswer
        },
        listGroups = { Result.Success(listed()) },
        // Never edited: what becomes of its history is GroupMembershipChangerTest's.
        history = { Result.Success(GroupHistoryResponse(current = serverVersion.inHistory(), previous = emptyList())) },
        group = { groups[it] },
        listPending = { pending.values.toList() },
        savePending = { change -> pending[change.groupUuid] = change },
        dropPending = { pending.remove(it) },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        adoptKey = { groupUuid, key ->
            groups[groupUuid] = groups.getValue(groupUuid).with(groupKey = key)
            pending.remove(groupUuid)
        },
        client = { Result.Success(alice) },
        receiveAccountDeletions = {},
        sealKeyCopy = keyCopies::seal,
        resendScheduler = resends,
    )

    private val changer = GroupMembershipChanger(
        userUuid = { ALICE },
        restoreContacts = { Result.Success(Unit) },
        contacts = { listOf(BOB, CAROL, DAVE) },
        blocked = { emptyList() },
        client = { Result.Success(alice) },
        consumeKeyPackage = { error("Alice adds nobody here.") },
        group = { groups[it] },
        hasPendingChange = { it in pending || it in pendingCreations },
        savePending = { change -> pending[change.groupUuid] = change },
        bookResend = sender::bookResend,
        send = sender::send,
        ownProfileKey = { null },
        contactProfileKey = { null },
        learnedProfileKey = { null },
        oweProfileKey = {},
    )

    private val groupSync = GroupSync(
        userUuid = { ALICE },
        resendPending = { Result.Success(Unit) },
        resendMembershipChanges = sender::resendPending,
        // What becomes of the copies of the group keys is GroupKeyCopiesTest's.
        openKeyCopies = { Result.Success(emptyMap()) },
        putUpKeyCopies = { Result.Success(Unit) },
        restoreContacts = { Result.Success(Unit) },
        listGroups = { Result.Success(listed()) },
        storedGroups = { groups.values.toList() },
        declinedGroups = { emptyList() },
        listMessages = { groupUuid ->
            if (groupUuid in unreachableMessages) {
                Result.Error(NetworkError.NO_INTERNET)
            } else {
                Result.Success(ListGroupMessagesResponse(messages = waiting[groupUuid].orEmpty().toList()))
            }
        },
        acknowledge = { groupUuid, messageUuids ->
            steps += "acknowledgement"
            acknowledged.getOrPut(groupUuid) { mutableListOf() } += messageUuids
            waiting[groupUuid]?.removeAll { it.uuid in messageUuids }
            Result.Success(Unit)
        },
        leave = { error("Alice declines nothing here.") },
        isUnblockedContact = { it in setOf(BOB, CAROL, DAVE) },
        client = { Result.Success(alice) },
        save = { _, _ -> error("Alice joins nothing here.") },
        memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        updateKey = { _, _ -> error("Nobody hands Alice a key here.") },
        updateInformation = { _, _ -> error("Alice edits nothing here.") },
        markLeft = { error("Nobody removes Alice here.") },
        recordDeclined = { error("Alice declines nothing here.") },
        forgetDeclined = { error("Alice declines nothing here.") },
        removeDeparted = changer::removeUnderGroupsLock,
        fullName = { null },
        hasPendingChange = { it in pending || it in pendingCreations },
        isBlocked = { false },
        setHandedProfileKey = { _, _ -> },
        takeOwnerKey = { userUuid, profileEncryptionKey, _ -> ownerKeys[userUuid] = profileEncryptionKey },
        fillGap = { _, _ -> },
        restoreMemberProfiles = { Result.Success(Unit) },
        readMemberNames = { Result.Success(Unit) },
        backUpMemberProfiles = { Result.Success(Unit) },
        handOutProfileKey = { Result.Success(Unit) },
        resendScheduler = resends,
    )

    /** As the server takes a leave, the member's device declining the group. */
    @Test
    fun `a member who left is removed in one Commit and the key rotated after it`() = runBlocking<Unit> {
        serverMembers -= CAROL

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(CAROL), removals.single().removedMemberUuids)
        assertEquals(listOf("removal", "rotation"), steps)
        assertEquals(rowsOf(ALICE, BOB, DAVE).toSet(), members.getValue(GROUP).toSet())
        assertEquals(setOf(ALICE, BOB, DAVE), aliceRoster().memberUuids.toSet())
        assertFalse(groupKey.contentEquals(groups.getValue(GROUP).groupKey))
        assertTrue(pending.isEmpty())
    }

    /** Nobody is told: the server cannot tell a member who declined the group from one who left it. */
    @Test
    fun `a member removed for leaving is told about to nobody`() = runBlocking<Unit> {
        serverMembers -= CAROL

        val pass = groupSync.sync(contactsTakenIn = true)

        assertTrue(pass.joined.isEmpty())
        assertTrue(pass.removed.isEmpty())
    }

    /** Dave deleted his account, which takes him off the server's list without a Commit. */
    @Test
    fun `every member gone goes in one Commit deleted accounts included`() = runBlocking<Unit> {
        serverMembers -= CAROL
        serverMembers -= DAVE

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertEquals(setOf(CAROL, DAVE), removals.single().removedMemberUuids.toSet())
        assertEquals(1, rotations.size)
        assertEquals(setOf(ALICE, BOB), aliceRoster().memberUuids.toSet())
    }

    @Test
    fun `a removal made is not made again`() = runBlocking<Unit> {
        serverMembers -= CAROL

        groupSync.sync(contactsTakenIn = true)

        val epoch = aliceRoster().epoch

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertEquals(1, removals.size)
        assertEquals(1, rotations.size)
        assertEquals(epoch, aliceRoster().epoch)
    }

    @Test
    fun `a group nobody left is not changed`() = runBlocking<Unit> {
        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(steps.isEmpty())
        assertEquals(1L, aliceRoster().epoch)
        assertTrue(pending.isEmpty())
    }

    /**
     * Bob's key, posted in the epoch the removal takes the group past, is read first, and
     * acknowledged, before the removal goes out.
     */
    @Test
    fun `what the members posted is read before the removal`() = runBlocking<Unit> {
        val bobGroup = bob.joinGroup(
            welcome = creation.welcome,
            expectedGroupUuid = GROUP,
            expectedMembers = listOf(ALICE, CAROL, DAVE),
            pinMembers = false,
        )
        val bobKey = generateSymmetricEncryptionKey()
        val post = bob.encryptApplicationMessage(
            mlsGroupId = bobGroup,
            payload = ProfileKeyUpdate(profileEncryptionKey = bobKey).serialize(),
        )

        waiting[GROUP] = mutableListOf(
            GroupMessageDto(
                uuid = "post",
                senderUserUuid = BOB,
                ciphertext = Base64.Default.encode(post),
                createdAt = "2026-10-28T12:00:00Z",
            ),
        )
        serverMembers -= CAROL

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertContentEquals(bobKey, ownerKeys[BOB])
        assertEquals(listOf("post"), acknowledged[GROUP]?.toList())
        assertEquals(listOf("acknowledgement", "removal", "rotation"), steps)
    }

    /**
     * Past a message that could not be read, a Commit would take the group on and leave it unread
     * for good: the removal waits for the drain to catch up.
     */
    @Test
    fun `a group the drain could not catch up with is changed once it does`() = runBlocking<Unit> {
        serverMembers -= CAROL
        unreachableMessages += GROUP

        val failed = groupSync.sync(contactsTakenIn = true)

        assertEquals(GroupSyncError.Network(NetworkError.NO_INTERNET), failed.failure)
        assertTrue(removals.isEmpty())

        unreachableMessages.clear()

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertEquals(listOf(CAROL), removals.single().removedMemberUuids)
    }

    /** The creation sent again until the server is heard to take it, which it may have. */
    @Test
    fun `a group whose creation is pending is not changed`() = runBlocking<Unit> {
        pendingCreations += GROUP
        serverMembers -= CAROL

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(steps.isEmpty())
        assertEquals(1L, aliceRoster().epoch)
    }

    /**
     * Sent again, the same removal, at every sync until the server takes it, and none built over
     * it meanwhile: the group is left alone while it is pending.
     */
    @Test
    fun `a removal over the limit is sent again and nothing more is built until it is taken`() =
        runBlocking<Unit> {
            serverMembers -= CAROL
            removeAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 30)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertEquals(MembershipChangeKind.REMOVAL, pending[GROUP]?.kind)
            assertTrue(30L in resends.seconds)
            assertTrue(rotations.isEmpty())

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertEquals(2, removals.size)

            removeAnswer = GroupMembershipAnswer.Made

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertEquals(3, removals.size)
            assertEquals(1, removals.map { it.nonce }.toSet().size)
            assertEquals(1, rotations.size)
            assertTrue(pending.isEmpty())
            assertEquals(setOf(ALICE, BOB, DAVE), aliceRoster().memberUuids.toSet())
        }

    /** Refused, the Commit is discarded and the group stays as it was, for the next sync to try again. */
    @Test
    fun `a removal the server refuses fails the sync and leaves the group as it was`() = runBlocking<Unit> {
        serverMembers -= CAROL
        removeAnswer = GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertEquals(
            GroupSyncError.Membership(GroupMembershipError.Network(NetworkError.BAD_REQUEST)),
            pass.failure,
        )
        assertTrue(pending.isEmpty())
        assertTrue(rotations.isEmpty())

        val roster = aliceRoster()

        assertFalse(roster.hasPendingCommit)
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), roster.memberUuids.toSet())
    }

    /** It lists a group to its current members and its owner, so this is one the server lost. */
    @Test
    fun `a group the server does not list is not changed`() = runBlocking<Unit> {
        isListed = false

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(steps.isEmpty())
        assertEquals(1L, aliceRoster().epoch)
    }

    /**
     * Bob's group, which the server says Alice owns, as once his account is deleted: its members
     * take Commits from him alone, whoever the server lists.
     */
    @Test
    fun `a group the user did not create is not changed whoever the server lists as its owner`() =
        runBlocking<Unit> {
            val bobsCreation = bob.createGroup(
                groupUuid = OTHER_GROUP,
                invitees = listOf(alice.inviteeKeyPackage(), carol.inviteeKeyPackage()),
                welcomePayload = welcomePayloadOf(groupKey = groupKey, profileKeys = emptyMap()),
            )
            val aliceInBobs = alice.joinGroup(
                welcome = bobsCreation.welcome,
                expectedGroupUuid = OTHER_GROUP,
                expectedMembers = listOf(BOB, CAROL),
                pinMembers = false,
            )

            groups[OTHER_GROUP] = createdGroup(groupUuid = OTHER_GROUP, ownerUuid = BOB, mlsGroupId = aliceInBobs)
            members[OTHER_GROUP] = rowsOf(BOB, ALICE, CAROL, groupUuid = OTHER_GROUP, creatorUuid = BOB)
            otherListings += listingOf(groupUuid = OTHER_GROUP, ownerUuid = ALICE, memberUuids = listOf(ALICE, BOB))

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(steps.isEmpty())
            assertEquals(
                setOf(ALICE, BOB, CAROL),
                alice.rosterOf(mlsGroupId = aliceInBobs).memberUuids.toSet(),
            )
        }

    /**
     * A log-out took its MLS state, so it can never be changed from this device again: its members
     * are the server's, Carol gone from them at once.
     */
    @Test
    fun `a group brought back after a log-out takes the server's members and is not changed`() =
        runBlocking<Unit> {
            groups[GROUP] = createdGroup(mlsGroupId = null)
            serverMembers -= CAROL

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(steps.isEmpty())
            assertEquals(rowsOf(ALICE, BOB, DAVE).toSet(), members.getValue(GROUP).toSet())
        }

    /** The sync holds the groups lock as it removes them, which is not reentrant. */
    @Test
    fun `removing those who left does not wait on the lock the sync holds`() = runBlocking<Unit> {
        serverMembers -= CAROL

        val pass = assertNotNull(withTimeoutOrNull(timeMillis = 10_000) { groupSync.sync(contactsTakenIn = true) })

        assertNull(pass.failure)
        assertEquals(1, removals.size)
    }

    /** Every group the server lists to Alice. */
    private fun listed(): List<GroupDto> {
        val own = if (isListed) listingOf(groupUuid = GROUP, ownerUuid = ALICE, memberUuids = serverMembers) else null

        return listOfNotNull(own) + otherListings
    }

    private fun listingOf(groupUuid: String, ownerUuid: String, memberUuids: List<String>) =
        listedGroupOf(uuid = groupUuid, ownerUuid = ownerUuid, version = serverVersion, memberUuids = memberUuids)

    /** [groupUuid] as this device holds it, created by [ownerUuid], in the MLS group [mlsGroupId]. */
    private fun createdGroup(
        groupUuid: String = GROUP,
        ownerUuid: String = ALICE,
        mlsGroupId: ByteArray? = creation.mlsGroupId,
    ) = GroupEntity(
        uuid = groupUuid,
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

    private suspend fun aliceRoster() = alice.rosterOf(mlsGroupId = creation.mlsGroupId)
}

/** The rows of the members [userUuids] of the group [groupUuid], [creatorUuid] their admin. */
private fun rowsOf(vararg userUuids: String, groupUuid: String = GROUP, creatorUuid: String = ALICE) =
    userUuids.map { GroupMemberEntity(userUuid = it, groupUuid = groupUuid, isAdmin = it == creatorUuid) }

/** [this] as `GroupDao.adoptRotatedKey` leaves it. */
private fun GroupEntity.with(groupKey: ByteArray) = GroupEntity(
    uuid = uuid,
    title = title,
    description = description,
    currency = currency,
    infoRevision = infoRevision,
    ownerUuid = ownerUuid,
    mlsGroupId = mlsGroupId,
    groupKey = groupKey,
    isBackedUp = false,
    isLeft = isLeft,
    handedProfileKeyHash = handedProfileKeyHash,
)
