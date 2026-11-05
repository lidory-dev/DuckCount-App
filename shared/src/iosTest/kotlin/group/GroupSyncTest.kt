package group

import contact.ProfileKeyUpdate
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.data.remote.DirectMessageDto
import database.data.remote.ListDirectMessagesResponse
import database.domain.CurrencyCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import message.DirectMessageEnvelope
import message.DirectMessageHandler
import message.DirectMessageHandling
import message.DirectMessageSync
import message.ResendsBooked
import mls.ApplicationMessageReading
import mls.InMemoryMlsStore
import mls.addMembersTo
import mls.applyPendingCommitIn
import mls.encryptApplicationMessage
import mls.holdsGroup
import mls.inviteeKeyPackage
import mls.joinGroupFrom
import mls.readApplicationMessage
import mls.removeMembersFrom
import mls.updateIn
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import vault.VaultError
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
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** What every group here is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/** What Dave seals for the group he posts the Welcome of Alice's group again as. */
private val DAVES_FIELDS = GroupFields(
    title = "Dave's trip",
    description = "Somewhere else",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What Bob's group sync makes of the groups the server lists for him: groups somebody created
 * with him and Carol in them, through the library itself as [GroupCreator] creates them, and his
 * own creations still waiting for an answer. The database, the key ring and the server are stood
 * in for, the server listing each group with the copy of its key it holds for Bob, see
 * [ServerKeyCopies].
 *
 * Every group Bob declines he leaves on the server, which then lists it to him no more and drops
 * what waited for him there, unless a test has it answer otherwise.
 *
 * Alice and Dave are Bob's contacts, Alice pinned as becoming a contact pins her, Dave reached over
 * MLS by no device of Bob's yet, unless a test pins him too. Carol is nobody to Bob, and Mallory a
 * stranger.
 */
class GroupSyncTest {

    private val bobStore = InMemoryMlsStore()

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = bobStore)
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())
    private val mallory = MlsClient(userUuid = MALLORY, secret = ByteArray(32) { 7 }, store = InMemoryMlsStore())

    /** Who Bob's contacts are, see `ContactDao.isUnblocked`. */
    private val contacts = setOf(ALICE, DAVE)

    /** Who the vault blocks, see `IdentityConflictDao`. */
    private var blocked = emptySet<String>()

    private val names = mapOf(ALICE to "Alice", DAVE to "Dave")

    /**
     * The groups the server lists for Bob, whether it can be asked at all, and the links back
     * through their keys the creators' Commits stored, see [GroupKeyChain].
     */
    private val listed = mutableListOf<GroupDto>()
    private var isServerReachable = true
    private val serverLinks = mutableListOf<GroupKeyLinkDto>()

    /** What waits for Bob in each group, and the groups whose messages cannot be asked for. */
    private val waiting = mutableMapOf<String, MutableList<GroupMessageDto>>()
    private val unreachableMessages = mutableSetOf<String>()

    /** Which groups' messages were asked for, and which messages were acknowledged in each. */
    private val fetched = mutableListOf<String>()
    private val acknowledged = mutableMapOf<String, MutableList<String>>()

    private val groups = mutableMapOf<String, GroupEntity>()
    private val members = mutableMapOf<String, List<GroupMemberEntity>>()

    /**
     * The generations of each group's key saved with it, see `GroupKeyEntity`, and the signature
     * key recorded for each of its members, see `GroupMemberSignatureKeyEntity`.
     */
    private val heldKeys = mutableMapOf<String, Set<Long>>()
    private val signatureKeys = mutableMapOf<String, Map<String, ByteArray>>()
    private val pending = mutableMapOf<String, PendingGroupCreationEntity>()
    private val declined = mutableSetOf<String>()

    /**
     * The groups Bob asked the server to leave, in order, what it answers, and what runs as each
     * leave is asked for.
     */
    private val left = mutableListOf<String>()
    private var leaveAnswer: GroupLeaveAnswer = GroupLeaveAnswer.Left
    private var onLeave: suspend () -> Unit = {}

    /** The groups a change of Bob's to their members waits on the server for, see `GroupDao.hasPendingChange`. */
    private val pendingChanges = mutableSetOf<String>()

    /**
     * The keys to the members' profiles the sync took, from their owners and relayed, see
     * `MemberProfileRepository`.
     */
    private val ownerKeys = mutableMapOf<String, ByteArray>()
    private val relayedKeys = mutableMapOf<String, ByteArray>()

    /** Whether saving a group fails, as the database failing would. */
    private var isDatabaseFailing = false

    /** What runs each time the sync saves a group, once the library has joined it. */
    private var onSave: suspend () -> Unit = {}

    /**
     * What runs each time the sync asks whether somebody is an unblocked contact: past the
     * inspection of a Welcome, and before its join.
     */
    private var onContactChecked: suspend () -> Unit = {}

    /**
     * What runs as the sync posts the user's own key, once every group is caught up, then as it
     * reads the members' names, and as it backs them up, with the groups free again.
     */
    private var onHandOut: suspend () -> Unit = {}
    private var onMemberNamesRead: suspend () -> Unit = {}
    private var onMemberProfilesBackedUp: suspend () -> Unit = {}

    /** The copies of Bob's group keys the server holds, and the key ring they are sealed with. */
    private val server = ServerKeyCopies()
    private val keyCopies = server.groupKeyCopies(groups)

    /** The Welcome of the last group [invite] had created, its MLS group, and who created it. */
    private var welcome: ByteArray? = null
    private var mlsGroupId: ByteArray? = null
    private var inviterUuid: String? = null

    /** What the server answers a creation sent again. */
    private var answer: GroupCreationAnswer = GroupCreationAnswer.Created

    private val resends = ResendsBooked()

    private val sender = GroupCreationSender(
        post = { answer },
        dropTaken = { groupUuid ->
            pending.remove(groupUuid)
            groups[groupUuid] = groups.getValue(groupUuid).markedBackedUp()
        },
        deleteRefused = { groupUuid ->
            pending.remove(groupUuid)
            members.remove(groupUuid)
            groups.remove(groupUuid)
        },
        listPending = { pending.values.toList() },
        client = { Result.Success(bob) },
        receiveAccountDeletions = {},
        resendScheduler = resends,
    )

    private val groupSync = GroupSync(
        userUuid = { BOB },
        resendPending = sender::resendPending,
        resendMembershipChanges = { Result.Success(Unit) },
        openKeyCopies = keyCopies::open,
        putUpKeyCopies = keyCopies::putUp,
        restoreContacts = { Result.Success(Unit) },
        listGroups = {
            if (isServerReachable) {
                Result.Success(listed.map(server::listing))
            } else {
                Result.Error(NetworkError.NO_INTERNET)
            }
        },
        storedGroups = { groups.values.toList() },
        declinedGroups = { declined.toList() },
        listMessages = { groupUuid ->
            fetched += groupUuid
            if (groupUuid in unreachableMessages) {
                Result.Error(NetworkError.NO_INTERNET)
            } else {
                Result.Success(ListGroupMessagesResponse(messages = waiting[groupUuid].orEmpty().toList()))
            }
        },
        acknowledge = { groupUuid, messageUuids ->
            acknowledged.getOrPut(groupUuid) { mutableListOf() } += messageUuids
            waiting[groupUuid]?.removeAll { it.uuid in messageUuids }
            Result.Success(Unit)
        },
        leave = { groupUuid ->
            onLeave()
            left += groupUuid

            // As the server takes it: the group is listed to Bob no more, and what waited for him
            // there is dropped.
            if (leaveAnswer == GroupLeaveAnswer.Left) {
                listed.removeAll { it.uuid == groupUuid }
                waiting.remove(groupUuid)
            }

            leaveAnswer
        },
        isUnblockedContact = {
            onContactChecked()
            it in contacts && it !in blocked
        },
        client = { Result.Success(bob) },
        save = { group, groupMembers, keys, groupSignatureKeys ->
            onSave()
            check(!isDatabaseFailing) { "The database is failing." }
            groups[group.uuid] = group
            members[group.uuid] = groupMembers
            heldKeys[group.uuid] = keys.map { it.generation }.toSet()
            signatureKeys[group.uuid] = groupSignatureKeys.associate { it.userUuid to it.signatureKey }
        },
        memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        recordSignatureKeys = { recorded ->
            recorded.forEach {
                signatureKeys[it.groupUuid] = signatureKeys[it.groupUuid].orEmpty() + (it.userUuid to it.signatureKey)
            }
        },
        takeKeys = { groupUuid, keys ->
            heldKeys[groupUuid] = heldKeys[groupUuid].orEmpty() + keys.map { it.generation }
        },
        updateInformation = { _, _ -> error("Alice edits nothing here.") },
        markLeft = { error("Nobody is removed from a group here.") },
        recordDeclined = { groupUuid -> declined += groupUuid },
        forgetDeclined = { groupUuid -> declined -= groupUuid },
        removeDeparted = { _, _ -> error("No group Bob created is listed here.") },
        replaceRefused = { _, _ -> error("No Commit is refused for good here.") },
        remakeOutdated = { Result.Success(null) },
        settleUnlisted = { _, _ -> Result.Success(Unit) },
        keyOf = { _, _, _ -> error("No key is got back through the chain here.") },
        checkKeyLinks = { Result.Success(Unit) },
        setAwaitingOwner = { _, _ -> },
        keyLinks = { Result.Success(serverLinks.toList()) },
        now = { error("Nobody departed from a group here.") },
        fullName = { names[it] },
        fullNameEvenIfHidden = { names[it] },
        hasPendingChange = { it in pending || it in pendingChanges },
        hasPendingCreation = { false },
        markDeleted = { error("Nobody deletes a group here.") },
        isBlocked = { it in blocked },
        setHandedProfileKey = { groupUuid, handedProfileKeyHash ->
            groups[groupUuid] = groups.getValue(groupUuid).withHanded(handedProfileKeyHash)
        },
        takeOwnerKey = { userUuid, profileEncryptionKey, _ -> ownerKeys[userUuid] = profileEncryptionKey },
        fillGap = { userUuid, profileEncryptionKey -> relayedKeys.getOrPut(userUuid) { profileEncryptionKey } },
        restoreMemberProfiles = { Result.Success(Unit) },
        readMemberNames = {
            onMemberNamesRead()
            Result.Success(Unit)
        },
        backUpMemberProfiles = {
            onMemberProfilesBackedUp()
            Result.Success(Unit)
        },
        handOutProfileKey = {
            onHandOut()
            Result.Success(Unit)
        },
        rebuildWakeList = {},
        resendScheduler = resends,
    )

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
    }

    @Test
    fun `a group a contact created is joined and saved with its members and its key`() = runBlocking<Unit> {
        val groupKey = invite(creator = alice)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(
            listOf(GroupArrival(groupUuid = GROUP, title = "Ski trip", adderUuid = ALICE, adderFullName = "Alice")),
            pass.joined,
        )

        val group = assertNotNull(groups[GROUP])

        assertEquals("Ski trip", group.title)
        assertEquals("Chalet and lift passes", group.description)
        assertEquals(CurrencyCatalog.defaultCurrency, group.currency)
        assertEquals(ALICE, group.ownerUuid)
        assertContentEquals(groupKey, group.groupKey)
        assertTrue(bob.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))

        assertEquals(
            setOf(
                GroupMemberEntity(userUuid = ALICE, groupUuid = GROUP, isAdmin = true),
                GroupMemberEntity(userUuid = BOB, groupUuid = GROUP, isAdmin = false),
                GroupMemberEntity(userUuid = CAROL, groupUuid = GROUP, isAdmin = false),
            ),
            members.getValue(GROUP).toSet(),
        )

        assertContentEquals(groupKey, server.keyOf(GROUP))
        assertTrue(group.isBackedUp)
        assertEquals(listOf(welcomeOf(GROUP)), acknowledged[GROUP]?.toList())
        assertTrue(declined.isEmpty())
    }

    /**
     * The key of the epoch the Welcome lets Bob in at, which his device derives as Alice's did, as its
     * generation, and the tree it seats him in each member's identity.
     */
    @Test
    fun `a group joined holds the key of the epoch it is joined at as its generation and each member's signature key`() =
        runBlocking<Unit> {
            val groupKey = invite(creator = alice)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val group = assertNotNull(groups[GROUP])

            assertEquals(FIRST_KEY_GENERATION, group.keyGeneration)
            assertContentEquals(groupKey, group.groupKey)
            assertEquals(setOf(FIRST_KEY_GENERATION), heldKeys[GROUP])
            assertEquals(FIRST_KEY_GENERATION, server.generationOf(GROUP))
            assertEquals(setOf(ALICE, BOB, CAROL), signatureKeys[GROUP]?.keys)
            assertContentEquals(alice.signaturePublicKey(), signatureKeys[GROUP]?.get(ALICE))
            assertContentEquals(carol.signaturePublicKey(), signatureKeys[GROUP]?.get(CAROL))
        }

    /**
     * Nothing this device stores reads once it locks, and the pass stops there: the group was
     * recorded joined before, and the next pass finds it joined, with nothing to tell.
     */
    @Test
    fun `a group joined is reported even when the device locks as the user's key is posted`() =
        runBlocking<Unit> {
            invite(creator = alice)

            var isNameRead = false

            onHandOut = { error("Cannot access Keystore key - device is locked") }
            onMemberNamesRead = { isNameRead = true }

            val pass = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.DeviceLocked, pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertNotNull(groups[GROUP])
            assertFalse(isNameRead)
        }

    /** With the groups free again, the backup writes into the vault. */
    @Test
    fun `a group joined is reported even when the device locks as the members are backed up`() =
        runBlocking<Unit> {
            invite(creator = alice)

            onMemberProfilesBackedUp = { error("Cannot access Keystore key - device is locked") }

            val pass = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.DeviceLocked, pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
        }

    /** The push worker waits for an unlock on it, rather than retrying what a lock would fail again. */
    @Test
    fun `the device locking is reported over whatever failed before it`() = runBlocking<Unit> {
        invite(creator = alice)
        unreachableMessages += GROUP

        onMemberNamesRead = { error("Cannot access Keystore key - device is locked") }

        assertEquals(GroupSyncError.DeviceLocked, groupSync.sync(contactsTakenIn = true).failure)
    }

    /** WorkManager stopping the worker, say, a CancellationException being an IllegalStateException. */
    @Test
    fun `a sync cancelled is not taken for a device that locked`() = runBlocking<Unit> {
        invite(creator = alice)

        onHandOut = { throw CancellationException("Stopped") }

        assertFailsWith<CancellationException> { groupSync.sync(contactsTakenIn = true) }
    }

    /**
     * The owner the server lists is the creator until their account is deleted, which hands the
     * group to another member, whose Commits no member's device would take.
     */
    @Test
    fun `whoever wrote the Welcome is recorded as the creator and not the owner the server lists`() =
        runBlocking<Unit> {
            invite(creator = alice)

            listed[0] = listed[0].copy(ownerUuid = CAROL)

            groupSync.sync(contactsTakenIn = true)

            assertEquals(ALICE, groups.getValue(GROUP).ownerUuid)
            assertEquals(listOf(ALICE), members.getValue(GROUP).filter { it.isAdmin }.map { it.userUuid })
        }

    /** Asked about only for what waits there for the group's members, see [GroupSyncMembershipTest]. */
    @Test
    fun `a group joined is never joined or told about again`() = runBlocking<Unit> {
        invite(creator = alice)
        groupSync.sync(contactsTakenIn = true)
        fetched.clear()

        val before = mlsState()
        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertEquals(listOf(GROUP), fetched)
        assertEquals(before, mlsState())
    }

    @Test
    fun `a group a stranger created is left and never joined or asked about again`() = runBlocking<Unit> {
        invite(creator = mallory)

        assertDeclinedStoringNothing()
        assertTrue(isWelcomeUnspent())

        fetched.clear()
        groupSync.sync(contactsTakenIn = true)

        assertTrue(fetched.isEmpty())
        assertEquals(listOf(GROUP), left)
    }

    @Test
    fun `a group a blocked contact created is left and never joined or asked about again`() =
        runBlocking<Unit> {
            blocked = setOf(ALICE)
            invite(creator = alice)

            assertDeclinedStoringNothing()
            assertTrue(isWelcomeUnspent())

            fetched.clear()
            groupSync.sync(contactsTakenIn = true)

            assertTrue(fetched.isEmpty())
            assertEquals(listOf(GROUP), left)
        }

    /** Mallory may have become a contact in what the contact sync could not take in. */
    @Test
    fun `a group a stranger created is left waiting while the contacts could not all be taken in`() =
        runBlocking<Unit> {
            invite(creator = mallory)

            val pass = groupSync.sync(contactsTakenIn = false)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertTrue(groups.isEmpty())
            assertTrue(declined.isEmpty())
            assertTrue(left.isEmpty())
            assertNull(acknowledged[GROUP])
            assertTrue(isWelcomeUnspent())
        }

    @Test
    fun `a group a contact created is joined while the contacts could not all be taken in`() = runBlocking<Unit> {
        invite(creator = alice)

        val pass = groupSync.sync(contactsTakenIn = false)

        assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
        assertTrue(GROUP in groups)
    }

    /** The join would check Dave against nothing, and he would be whoever the Welcome said. */
    @Test
    fun `a group a contact pinned to no identity created is not joined`() = runBlocking<Unit> {
        invite(creator = dave)

        assertDeclinedStoringNothing()
        assertTrue(isWelcomeUnspent())
    }

    /** The server's word for who sent the Welcome stands for nothing once MLS says who wrote it. */
    @Test
    fun `a Welcome a stranger wrote is refused whoever the server says sent it`() = runBlocking<Unit> {
        invite(creator = mallory)
        waiting[GROUP] = waiting.getValue(GROUP).mapTo(mutableListOf()) { it.copy(senderUserUuid = ALICE) }

        assertDeclinedStoringNothing()
    }

    /** Somebody holding Alice's UUID under a key of their own, which the one Bob pinned for her refuses. */
    @Test
    fun `a Welcome from somebody passing for a pinned contact is declined and stores nothing`() =
        runBlocking<Unit> {
            val impostor = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 9 }, store = InMemoryMlsStore())

            invite(creator = impostor)

            assertDeclinedStoringNothing()
        }

    /** Whatever the server was handed as a Welcome: it stores one without reading it. */
    @Test
    fun `a Welcome that does not read as one is declined and stores nothing`() = runBlocking<Unit> {
        invite(creator = alice)
        waiting[GROUP] = waiting.getValue(GROUP).mapTo(mutableListOf()) {
            it.copy(ciphertext = Base64.Default.encode(ByteArray(64) { 5 }))
        }

        assertDeclinedStoringNothing()
    }

    /** As above, before the inspection: what the server stored is not even Base64. */
    @Test
    fun `a Welcome that is not Base64 is declined and left`() = runBlocking<Unit> {
        invite(creator = alice)
        waiting[GROUP] = waiting.getValue(GROUP).mapTo(mutableListOf()) { it.copy(ciphertext = "not base64!") }

        assertDeclinedStoringNothing()
    }

    /**
     * Alice removed Bob while he was logged out and added him back: the Commit removing him waits
     * before her Welcome, unread, and the server dropped his copy of the group's key with the
     * removal. Her Welcome is joined from, the creator being the owner the server lists.
     */
    @Test
    fun `a Welcome of the owner's behind what waited from an earlier membership is joined from`() =
        runBlocking<Unit> {
            val groupKey = invite(creator = alice)

            waiting.getValue(GROUP).add(0, unreadable(uuid = "removal"))

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
            assertEquals(listOf("removal", welcomeOf(GROUP)), acknowledged[GROUP]?.toList())
        }

    /**
     * Dave, a contact of Bob's, posting the Welcome of a group of his own under the UUID of
     * Alice's, behind something that is no way in: the owner the server lists is Alice, so his is
     * never joined from, and with none of hers the group is declined.
     */
    @Test
    fun `a Welcome of a member's own group behind what is no way in is not joined from`() = runBlocking<Unit> {
        bob.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())
        list(owner = ALICE, groupKey = generateSymmetricEncryptionKey())

        val davesGroupKey = generateSymmetricEncryptionKey()
        val davesGroup = dave.createGroup(
            groupUuid = GROUP,
            invitees = listOf(bob.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
        )

        waiting[GROUP] = mutableListOf(
            unreadable(uuid = "earlier"),
            GroupMessageDto(
                uuid = "daves-welcome",
                senderUserUuid = DAVE,
                ciphertext = Base64.Default.encode(davesGroup.welcome),
                createdAt = "2026-10-23T12:05:00Z",
                departedMemberUuids = emptyList(),
            ),
        )

        assertDeclinedStoringNothing()
    }

    /**
     * Nothing for the key: Bob derives it from the epoch the Welcome lets him in at. Nor any key to a
     * member's profile, which every member posts of their own once seated.
     */
    @Test
    fun `a Welcome handing over nothing is joined all the same`() = runBlocking<Unit> {
        invite(creator = alice, welcomePayload = null)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
        assertTrue(relayedKeys.isEmpty())
    }

    /** Its payload, whatever it holds, says nothing of the key, and hands no key to anybody's profile. */
    @Test
    fun `a Welcome whose payload does not read as one is joined with nothing taken from it`() = runBlocking<Unit> {
        invite(creator = alice, welcomePayload = ByteArray(32) { 9 })

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
        assertTrue(relayedKeys.isEmpty())
        assertTrue(ownerKeys.isEmpty())
    }

    /**
     * The server serving one group's fields under another group's UUID: the key is the epoch's,
     * known once joined alone, and the group joined is deleted again, declined and left.
     */
    @Test
    fun `fields sealed for another group are refused once joined`() = runBlocking<Unit> {
        invite(creator = alice, sealedFor = OTHER_GROUP)

        assertRefusedOnceJoined()
    }

    /**
     * Carol holds the group's key, and seals a version of her own, which the server lists: nothing
     * of the group is shown on Bob's device yet to keep instead, so it is refused as fields that do
     * not open are.
     */
    @Test
    fun `a version its creator did not sign is refused once joined`() = runBlocking<Unit> {
        invite(creator = alice, signer = carol)

        assertRefusedOnceJoined()
    }

    /** Fields under a key of no epoch of the group's, which no member could have sealed them under. */
    @Test
    fun `fields sealed under a key the group does not give are refused once joined`() = runBlocking<Unit> {
        invite(creator = alice, groupKey = generateSymmetricEncryptionKey())

        assertRefusedOnceJoined()
    }

    /**
     * Alice moved the group on before adding Bob, editing it in the second epoch: he joins at the
     * third, and gets the second's key, which the information is sealed under, and the first's, the
     * currency's, back through the links her Commits stored, before anything is joined.
     */
    @Test
    fun `a group joined past its first epoch opens its information through the chain`() = runBlocking<Unit> {
        val creation = alice.createGroup(
            groupUuid = GROUP,
            invitees = listOf(carol.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
        )
        val keys = mutableMapOf(1L to alice.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP))

        suspend fun aliceApplies() {
            val applied = alice.applyPendingCommitIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)

            keys[applied.key.epoch] = applied.key.key
            serverLinks += GroupKeyLinkDto(
                generation = applied.key.epoch,
                publisherUuid = ALICE,
                previousKey = assertNotNull(
                    sealKeyLink(
                        previousKey = keys.getValue(applied.key.epoch - 1),
                        groupKey = applied.key.key,
                        groupUuid = GROUP,
                        generation = applied.key.epoch,
                    ),
                ),
            )
        }

        alice.updateIn(mlsGroupId = creation.mlsGroupId)
        aliceApplies()

        val addition = alice.addMembersTo(
            mlsGroupId = creation.mlsGroupId,
            invitees = listOf(bob.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
        )

        aliceApplies()

        listed += listedGroupOf(
            uuid = GROUP,
            ownerUuid = ALICE,
            version = FIELDS.sealedAs(
                creator = alice,
                groupKey = keys.getValue(2),
                groupUuid = GROUP,
                revision = 2,
                currencyKey = keys.getValue(1),
            ),
            memberUuids = listOf(ALICE, BOB, CAROL),
            keyGeneration = 3,
            informationKeyGeneration = 2,
        )
        waiting[GROUP] = mutableListOf(
            groupMessageOf(uuid = welcomeOf(GROUP), senderUuid = ALICE, message = addition.welcome),
        )

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })

        val group = assertNotNull(groups[GROUP])

        assertEquals(3L, group.keyGeneration)
        assertContentEquals(keys.getValue(3), group.groupKey)
        assertEquals(2L, group.infoRevision)
        assertEquals(setOf(1L, 2L, 3L), heldKeys[GROUP])
    }

    /** Signed by Alice as she wrote the Welcome, which is whose versions Bob's device shows from then on. */
    @Test
    fun `a group is joined at the revision of the version the server lists`() = runBlocking<Unit> {
        invite(creator = alice, revision = 3)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        val group = assertNotNull(groups[GROUP])

        assertEquals("Ski trip", group.title)
        assertEquals(3L, group.infoRevision)
    }

    /**
     * Dave, a member of Alice's group and a contact of Bob's, posting its Welcome again as a group
     * of his own, with fields he sealed for it under the key the Welcome handed him, listed before
     * Alice's: his is declined, and Alice's still joined.
     */
    @Test
    fun `a Welcome posted again under another group listed first is declined there and joined under its own`() =
        runBlocking<Unit> {
            val groupKey = inviteWithDave()

            postAgain(groupKey = groupKey)
            listed.reverse()

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(aliceAddedBob()), pass.joined)
            assertHeldUnderItsOwnGroupAlone(groupKey = groupKey)
        }

    /** As above, Alice's group passed over once, as when asking for its messages fails. */
    @Test
    fun `a Welcome posted again under another group while its own is passed over is declined there and joined later`() =
        runBlocking<Unit> {
            val groupKey = inviteWithDave()

            postAgain(groupKey = groupKey)
            unreachableMessages += GROUP

            val passedOver = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Network(NetworkError.NO_INTERNET), passedOver.failure)
            assertTrue(passedOver.joined.isEmpty())
            assertEquals(listOf(OTHER_GROUP), left)
            assertTrue(holdsNoGroup())

            unreachableMessages.clear()

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(aliceAddedBob()), pass.joined)
            assertHeldUnderItsOwnGroupAlone(groupKey = groupKey)
        }

    /**
     * Sealed to Bob's last-resort key package, which the join leaves unspent, so the Welcome still
     * opens once its own group is joined: the library refuses it for its id, derived from the UUID
     * of the group it was written for.
     */
    @Test
    fun `a Welcome posted again under another group once its own is joined is declined and stores nothing`() =
        runBlocking<Unit> {
            val groupKey = inviteWithDave(sealedToLastResort = true)

            groupSync.sync(contactsTakenIn = true)
            postAgain(groupKey = groupKey)

            assertFailsWith<MlsException.UnexpectedGroupId> {
                bob.inspectWelcome(welcome = assertNotNull(welcome), expectedGroupUuid = OTHER_GROUP)
            }

            val before = mlsState()
            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertEquals(before, mlsState())
            assertHeldUnderItsOwnGroupAlone(groupKey = groupKey)
        }

    /** Refused by the library before its id is even read, the first join having spent its key package. */
    @Test
    fun `a Welcome sealed to a one-time key package posted again once its own group is joined is declined`() =
        runBlocking<Unit> {
            val groupKey = inviteWithDave()

            groupSync.sync(contactsTakenIn = true)
            postAgain(groupKey = groupKey)

            assertFailsWith<MlsException.ProtocolFailed> {
                bob.inspectWelcome(welcome = assertNotNull(welcome), expectedGroupUuid = OTHER_GROUP)
            }

            val before = mlsState()
            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())
            assertEquals(before, mlsState())
            assertHeldUnderItsOwnGroupAlone(groupKey = groupKey)
        }

    /**
     * Joined from Bob's last-resort key package, which the join leaves unspent, and deleted again
     * when saving it failed: the Welcome still opens, and the library refuses it as one to a group
     * this device joined before, which only the same group can be, its id derived from its UUID.
     */
    @Test
    fun `a Welcome joined before and lost with a group that could not be saved is declined the next time`() =
        runBlocking<Unit> {
            invite(creator = alice, sealedToLastResort = true)
            isDatabaseFailing = true

            val failed = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Storage, failed.failure)
            assertTrue(failed.joined.isEmpty())
            assertTrue(declined.isEmpty())
            assertNull(acknowledged[GROUP])

            // Deleted again, so what refuses the Welcome below is the record of its deletion.
            assertTrue(holdsNoGroup())

            isDatabaseFailing = false

            assertFailsWith<MlsException.GroupAlreadyJoined> {
                bob.inspectWelcome(welcome = assertNotNull(welcome), expectedGroupUuid = GROUP)
            }

            assertDeclinedStoringNothing()
        }

    /**
     * Carol, Bob's co-invitee and nobody to him, reads the group's id in its Welcome and gives a
     * contact request of her own the same id, which the library lets her do only by creating it
     * under the group's UUID. Opened as `ContactRequestRepository` opens one while Bob's sync is
     * between the inspection and the join, as a contact sync running alongside would, the request
     * is refused, its id not being a channel's, and the group is joined all the same.
     */
    @Test
    fun `a contact request given the id of a group being joined is refused and the group joined`() =
        runBlocking<Unit> {
            invite(creator = alice)

            val request = carol.createGroup(groupUuid = GROUP, invitees = listOf(bob.inviteeKeyPackage()), welcomePayload = null)
            var refusal: MlsException? = null

            onContactChecked = {
                onContactChecked = {}

                refusal = try {
                    bob.joinGroupFrom(welcome = request.welcome, peerUuid = CAROL, pinPeer = false)
                    null
                } catch (exception: MlsException) {
                    exception
                }
            }

            val pass = groupSync.sync(contactsTakenIn = true)

            assertIs<MlsException.UnexpectedGroupId>(refusal)
            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertContentEquals(request.mlsGroupId, groups.getValue(GROUP).mlsGroupId)
            assertTrue(declined.isEmpty())
        }

    /**
     * Alice, Bob's contact, sends him a direct message naming her group while his sync is between
     * joining it and saving it, as a push syncing his direct messages while the group list syncs
     * its groups would find it: read then, it would be from a pinned contact in a group the
     * database does not name yet, and the group would become the channel to her. It is read only
     * once the group is saved, and so left unread.
     */
    @Test
    fun `a direct message naming a group being joined is left unread`() = runBlocking<Unit> {
        invite(creator = alice)

        val group = assertNotNull(mlsGroupId)
        val message = alice.encryptApplicationMessage(mlsGroupId = group, payload = "hello".encodeToByteArray())
        val offered = mutableListOf<String>()
        var delivery: Job? = null

        onSave = {
            onSave = {}

            delivery = launch { directMessageSync(message = message, offered = offered).sync() }

            // Long enough for the message to be read, were it read now.
            withTimeoutOrNull(timeMillis = 500) { delivery?.join() }
        }

        assertEquals(listOf(GROUP), groupSync.sync(contactsTakenIn = true).joined.map { it.groupUuid })

        assertNotNull(delivery).join()

        assertTrue(offered.isEmpty())

        // Its key not spent: it was never read.
        assertIs<ApplicationMessageReading.Read>(bob.readApplicationMessage(mlsGroupId = group, message = message))
    }

    /** Storage failing says nothing about the Welcome, which is left waiting rather than declined. */
    @Test
    fun `a Welcome whose inspection cannot read the store is left waiting and joined at the next sync`() =
        runBlocking<Unit> {
            invite(creator = alice)

            // What the inspection reads to tell whether the group was joined before.
            bobStore.failing = setOf(MlsNamespace.GROUP_STATE)

            assertLeftWaitingOnStorage(groupSync.sync(contactsTakenIn = true))

            bobStore.failing = emptySet()

            assertJoinedAtLast(groupSync.sync(contactsTakenIn = true))
        }

    /** As above, past the inspection, the pin of the Welcome's writer being what cannot be read. */
    @Test
    fun `a Welcome whose writer's pin cannot be read is left waiting and joined at the next sync`() =
        runBlocking<Unit> {
            invite(creator = alice)

            // Only once the inspection, which reads the pins as well, is through.
            onContactChecked = { bobStore.failing = setOf(MlsNamespace.IDENTITY_PIN) }

            assertLeftWaitingOnStorage(groupSync.sync(contactsTakenIn = true))

            onContactChecked = {}
            bobStore.failing = emptySet()

            assertJoinedAtLast(groupSync.sync(contactsTakenIn = true))
        }

    /**
     * Carol deleting her account takes her off the server's list at once, and leaves her in the
     * tree of the Welcome until Alice's device next syncs and removes her, see
     * [GroupSync.dropDeparted].
     */
    @Test
    fun `a member who deleted their account since keeps nobody from joining and is recorded as a member`() =
        runBlocking<Unit> {
            invite(creator = alice, listedMembers = listOf(ALICE, BOB))

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
            assertEquals(
                setOf(
                    GroupMemberEntity(userUuid = ALICE, groupUuid = GROUP, isAdmin = true),
                    GroupMemberEntity(userUuid = BOB, groupUuid = GROUP, isAdmin = false),
                    GroupMemberEntity(userUuid = CAROL, groupUuid = GROUP, isAdmin = false),
                ),
                members.getValue(GROUP).toSet(),
            )
            assertTrue(declined.isEmpty())
        }

    /** As a log-out leaves Bob: the Welcome long acknowledged, the copy of the key listed, no MLS state. */
    @Test
    fun `a group listed with the user's copy of its key is brought back without a Welcome or MLS state`() =
        runBlocking<Unit> {
            val groupKey = generateSymmetricEncryptionKey()

            list(owner = ALICE, groupKey = groupKey)
            server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(pass.joined.isEmpty())

            val group = assertNotNull(groups[GROUP])

            assertEquals("Ski trip", group.title)
            assertNull(group.mlsGroupId)
            assertTrue(group.isBackedUp)
            assertContentEquals(groupKey, group.groupKey)
            assertEquals(setOf(ALICE, BOB, CAROL), members.getValue(GROUP).mapTo(HashSet()) { it.userUuid })

            assertTrue(fetched.isEmpty())
            assertTrue(server.putUp.isEmpty())
        }

    @Test
    fun `a group brought back records the revision of the version the server lists`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()

        list(owner = ALICE, groupKey = groupKey, revision = 4)
        server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertEquals(4L, assertNotNull(groups[GROUP]).infoRevision)
    }

    /** The generations before it come back through the chain as they are asked for, see [GroupKeyChain]. */
    @Test
    fun `a group brought back holds the key its copy holds as the generation the copy was put up as`() =
        runBlocking<Unit> {
            val groupKey = generateSymmetricEncryptionKey()

            val firstKey = generateSymmetricEncryptionKey()
            val secondKey = generateSymmetricEncryptionKey()

            list(owner = ALICE, groupKey = groupKey, currencyKey = firstKey, informationKeyGeneration = 3)
            server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP, keyGeneration = 3)
            server.generations[GROUP] = 3
            serverLinks += listOf(2L to (firstKey to secondKey), 3L to (secondKey to groupKey)).map { (generation, keys) ->
                GroupKeyLinkDto(
                    generation = generation,
                    publisherUuid = ALICE,
                    previousKey = assertNotNull(
                        sealKeyLink(previousKey = keys.first, groupKey = keys.second, groupUuid = GROUP, generation = generation),
                    ),
                )
            }

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val group = assertNotNull(groups[GROUP])

            assertEquals(3L, group.keyGeneration)
            assertContentEquals(groupKey, group.groupKey)
            // The first, the currency's, got back on the way, and the one between.
            assertEquals(setOf(1L, 2L, 3L), heldKeys[GROUP])
            assertTrue(server.putUp.isEmpty())
        }

    /**
     * The server listing the copy as a later generation than its own, which it opens as the key of
     * no generation but its own: taken for no copy at all, as one sealed for another group is.
     */
    @Test
    fun `a group listed with a copy of another generation than its own is declined as one with no copy`() =
        runBlocking<Unit> {
            val groupKey = generateSymmetricEncryptionKey()

            list(owner = ALICE, groupKey = groupKey)
            server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP, keyGeneration = 2)
            server.generations[GROUP] = 3

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(groups.isEmpty())
            assertEquals(listOf(GROUP), left)
        }

    /**
     * Alice is pinned, as a contact's pin comes back with the vault after a log-out: Carol's version
     * is not hers, and with nothing of the group shown to keep instead, it is not brought back,
     * as a group whose fields do not open is not, its Welcome waited for.
     */
    @Test
    fun `a group brought back whose version its creator did not sign is not brought back`() =
        runBlocking<Unit> {
            val groupKey = generateSymmetricEncryptionKey()

            list(owner = ALICE, groupKey = groupKey, signer = carol)
            server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(groups.isEmpty())
            assertTrue(declined.isEmpty())
            assertTrue(left.isEmpty())
        }

    /**
     * Mallory created the group, and is nobody to Bob, who holds no pin for her after a log-out:
     * there is nothing to hold the version against, and it is taken at its word, whoever signed it.
     */
    @Test
    fun `a group brought back whose creator this device holds no pin for is taken at its word`() =
        runBlocking<Unit> {
            val groupKey = generateSymmetricEncryptionKey()

            list(owner = MALLORY, groupKey = groupKey, signer = carol, revision = 2)
            server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val group = assertNotNull(groups[GROUP])

            assertEquals("Ski trip", group.title)
            assertEquals(MALLORY, group.ownerUuid)
            assertEquals(2L, group.infoRevision)
        }

    /**
     * The server listing one group's copy with another, which opens as the key of no group but the
     * one it was sealed for: taken for no copy at all, the group is declined, as below.
     */
    @Test
    fun `a group listed with a copy sealed for another group is declined as one with no copy`() =
        runBlocking<Unit> {
            val groupKey = generateSymmetricEncryptionKey()

            list(owner = ALICE, groupKey = groupKey)
            server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = OTHER_GROUP)

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(groups.isEmpty())
            assertEquals(listOf(GROUP), left)
            assertEquals(1, server.keyRingReads)
        }

    /**
     * The key ring not answering as the copies are opened is not the server holding none: taken for
     * that, the group would be declined and left, and Bob would lose a group that is his.
     */
    @Test
    fun `a group listed with a copy of its key is neither declined nor left while the key ring does not answer`() =
        runBlocking<Unit> {
            val groupKey = generateSymmetricEncryptionKey()

            list(owner = ALICE, groupKey = groupKey)
            server.copies[GROUP] = server.copyOf(groupKey = groupKey, sealedFor = GROUP)
            server.isKeyRingAnswering = false

            val pass = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Vault(VaultError.Network(NetworkError.UNREACHABLE)), pass.failure)
            assertTrue(groups.isEmpty())
            assertTrue(declined.isEmpty())
            assertTrue(left.isEmpty())
            assertTrue(fetched.isEmpty())

            server.isKeyRingAnswering = true

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            val group = assertNotNull(groups[GROUP])

            assertContentEquals(groupKey, group.groupKey)
            assertTrue(group.isBackedUp)
            assertTrue(left.isEmpty())
        }

    /** Most syncs find nothing to bring back, and reading the key ring is a request. */
    @Test
    fun `a group joined from its Welcome reads the key ring only to put its copy up`() = runBlocking<Unit> {
        invite(creator = alice)

        groupSync.sync(contactsTakenIn = true)

        assertEquals(1, server.keyRingReads)
        assertEquals(listOf(GROUP), server.putUp)
    }

    /**
     * As a log-out leaves a group joined before it whose key's copy never went up: the Welcome
     * acknowledged, no copy listed.
     */
    @Test
    fun `a group with no Welcome waiting and no copy of its key is declined and left and never asked about again`() =
        runBlocking<Unit> {
            list(owner = ALICE, groupKey = generateSymmetricEncryptionKey())

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(groups.isEmpty())
            assertEquals(listOf(GROUP), left)
            assertTrue(declined.isEmpty())
            assertEquals(listOf(GROUP), fetched)

            fetched.clear()

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(fetched.isEmpty())
            assertEquals(listOf(GROUP), left)
        }

    /**
     * Carol pinned to another identity between the inspection and the join, as a contact sync
     * running alongside could: the join refuses the Welcome for good.
     */
    @Test
    fun `a group whose join is refused for good is declined and left`() = runBlocking<Unit> {
        invite(creator = alice)

        onContactChecked = {
            onContactChecked = {}
            bob.pinIdentity(userUuid = CAROL, signatureKey = mallory.signaturePublicKey())
        }

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertTrue(groups.isEmpty())
        assertEquals(listOf(GROUP), left)
        assertTrue(declined.isEmpty())
        assertTrue(holdsNoGroup())
    }

    /** Asked again by the resend booked for it, the user having no other way to be woken for it. */
    @Test
    fun `a leave the server does not answer is kept and asked again without the group being decided again`() =
        runBlocking<Unit> {
            invite(creator = mallory)
            leaveAnswer = GroupLeaveAnswer.Failed(error = NetworkError.SERVER_ERROR)

            val failed = groupSync.sync(contactsTakenIn = true)

            assertEquals(GroupSyncError.Network(NetworkError.SERVER_ERROR), failed.failure)
            assertEquals(setOf(GROUP), declined)
            assertEquals(listOf(GROUP), left)
            assertEquals(listOf(60L), resends.seconds)
            assertNull(acknowledged[GROUP])

            fetched.clear()
            leaveAnswer = GroupLeaveAnswer.Left

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(fetched.isEmpty())
            assertEquals(listOf(GROUP, GROUP), left)
            assertTrue(declined.isEmpty())
            assertTrue(listed.isEmpty())
        }

    /** The next one would fare no better, and the resend booked goes for them all. */
    @Test
    fun `a leave the server does not answer holds back the others until the next sync`() = runBlocking<Unit> {
        list(owner = ALICE, groupKey = generateSymmetricEncryptionKey())
        list(owner = ALICE, groupKey = generateSymmetricEncryptionKey(), groupUuid = OTHER_GROUP)
        leaveAnswer = GroupLeaveAnswer.Failed(error = NetworkError.NO_INTERNET)

        val pass = groupSync.sync(contactsTakenIn = true)

        assertEquals(GroupSyncError.Network(NetworkError.NO_INTERNET), pass.failure)
        assertEquals(1, left.size)
        assertEquals(setOf(GROUP, OTHER_GROUP), declined)
    }

    /**
     * Only the proxy answers 404 for a group the server lists, as the server is redeployed: the
     * leave is kept and sent again, the group not decided on again. Once the server lists the
     * group no more, it forgot it or never had the user in it, and the leave is forgotten.
     */
    @Test
    fun `a leave answered 404 is kept and sent again while the group is listed and forgotten once it is not`() =
        runBlocking<Unit> {
            invite(creator = mallory)
            leaveAnswer = GroupLeaveAnswer.NotMember

            assertEquals(GroupSyncError.Network(NetworkError.NOT_FOUND), groupSync.sync(contactsTakenIn = true).failure)

            assertEquals(listOf(GROUP), left)
            assertEquals(setOf(GROUP), declined)
            assertEquals(listOf(60L), resends.seconds)
            assertNull(acknowledged[GROUP])

            fetched.clear()

            assertEquals(GroupSyncError.Network(NetworkError.NOT_FOUND), groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(fetched.isEmpty())
            assertEquals(listOf(GROUP, GROUP), left)
            assertEquals(setOf(GROUP), declined)
            assertEquals(listOf(60L, 60L), resends.seconds)

            listed.clear()

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(fetched.isEmpty())
            assertEquals(listOf(GROUP, GROUP, GROUP), left)
            assertTrue(declined.isEmpty())
            assertEquals(listOf(60L, 60L), resends.seconds)
        }

    /** The row recorded before the leave is asked for, the device locking as it is. */
    @Test
    fun `a group declined as the device locks is left by the next pass`() = runBlocking<Unit> {
        invite(creator = mallory)

        onLeave = { error("Cannot access Keystore key - device is locked") }

        assertEquals(GroupSyncError.DeviceLocked, groupSync.sync(contactsTakenIn = true).failure)

        assertEquals(setOf(GROUP), declined)
        assertTrue(left.isEmpty())

        fetched.clear()
        onLeave = {}

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(fetched.isEmpty())
        assertEquals(listOf(GROUP), left)
        assertTrue(declined.isEmpty())
    }

    /**
     * Dave unpinned when he first created the group, so it was declined and left. His device then
     * dropped Bob's leaf at its next sync, and added him back once Bob pinned him: the Welcome doing
     * so is decided on afresh, the first thing waiting there, and joined.
     */
    @Test
    fun `a group left once declined is joined when its creator adds the user back`() = runBlocking<Unit> {
        val groupKey = invite(creator = dave)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)
        assertEquals(listOf(GROUP), left)

        val daveGroup = assertNotNull(mlsGroupId)

        // Each of Dave's Commits stores the link back from the key of the epoch it leads to.
        var previous = groupKey

        suspend fun daveApplies() {
            val applied = dave.applyPendingCommitIn(mlsGroupId = daveGroup, groupUuid = GROUP)

            serverLinks += GroupKeyLinkDto(
                generation = applied.key.epoch,
                publisherUuid = DAVE,
                previousKey = assertNotNull(
                    sealKeyLink(
                        previousKey = previous,
                        groupKey = applied.key.key,
                        groupUuid = GROUP,
                        generation = applied.key.epoch,
                    ),
                ),
            )
            previous = applied.key.key
        }

        dave.removeMembersFrom(mlsGroupId = daveGroup, userUuids = listOf(BOB))
        daveApplies()

        val addition = dave.addMembersTo(
            mlsGroupId = daveGroup,
            invitees = listOf(bob.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
        )

        daveApplies()
        bob.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())

        list(owner = DAVE, groupKey = groupKey, keyGeneration = 3)
        waiting[GROUP] = mutableListOf(
            GroupMessageDto(
                uuid = "welcome-back",
                senderUserUuid = DAVE,
                ciphertext = Base64.Default.encode(addition.welcome),
                createdAt = "2026-10-24T12:00:00Z",
                departedMemberUuids = emptyList(),
            ),
        )

        val pass = groupSync.sync(contactsTakenIn = true)

        assertNull(pass.failure)
        assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
        assertEquals(DAVE, groups.getValue(GROUP).ownerUuid)
        assertEquals(listOf("welcome-back"), acknowledged[GROUP]?.toList())
        assertEquals(listOf(GROUP), left)
    }

    @Test
    fun `a creation the server takes at the next sync keeps its group backed up and nothing pending`() =
        runBlocking<Unit> {
            createPending()

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(GROUP in groups)
            assertTrue(groups.getValue(GROUP).isBackedUp)
            assertTrue(pending.isEmpty())
            assertTrue(resends.seconds.isEmpty())
        }

    @Test
    fun `a creation the server refuses at the next sync goes with its MLS group`() = runBlocking<Unit> {
        val mlsGroupId = createPending()
        answer = GroupCreationAnswer.Refused(error = NetworkError.BAD_REQUEST)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(groups.isEmpty())
        assertTrue(pending.isEmpty())
        assertFalse(bob.holdsGroup(mlsGroupId = mlsGroupId))
    }

    @Test
    fun `a creation still unanswered at the next sync stays pending and fails the sync`() = runBlocking<Unit> {
        createPending()
        answer = GroupCreationAnswer.Failed(error = NetworkError.SERVER_ERROR)

        assertEquals(GroupSyncError.Network(NetworkError.SERVER_ERROR), groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(GROUP in groups)
        assertTrue(GROUP in pending)
        assertEquals(listOf(60L), resends.seconds)
    }

    @Test
    fun `a creation still over the limit at the next sync stays pending with its resend booked`() =
        runBlocking<Unit> {
            createPending()
            answer = GroupCreationAnswer.RateLimited(retryAfterSeconds = 120)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertTrue(GROUP in pending)
            assertEquals(listOf(120L), resends.seconds)
        }

    /**
     * Bob created the group with Alice and adds Dave, the server taking the addition and the answer
     * getting lost: Alice, past the Commit, posts the key to her profile in the epoch it leads to,
     * which Bob's device reads only once it applied the Commit too. Read before, it would be
     * refused for good.
     */
    @Test
    fun `a key a member posts while the creator's change is pending is read once the change is settled`() =
        runBlocking<Unit> {
            val aliceGroupId = createdWithAlice()
            val bobGroupId = assertNotNull(groups.getValue(GROUP).mlsGroupId)

            val addition = bob.addMembersTo(
                mlsGroupId = bobGroupId,
                invitees = listOf(dave.inviteeKeyPackage()),
                welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
            )

            pendingChanges += GROUP

            alice.processUnderPolicy(mlsGroupId = aliceGroupId, message = addition.commit, groupUuid = GROUP, creatorUuid = BOB)

            val aliceKey = generateSymmetricEncryptionKey()
            val post = alice.encryptApplicationMessage(
                mlsGroupId = aliceGroupId,
                payload = ProfileKeyUpdate(profileEncryptionKey = aliceKey).serialize(),
            )

            waiting[GROUP] = mutableListOf(
                GroupMessageDto(
                    uuid = "post",
                    senderUserUuid = ALICE,
                    ciphertext = Base64.Default.encode(post),
                    createdAt = "2026-10-27T12:00:00Z",
                    departedMemberUuids = emptyList(),
                ),
            )

            val waited = groupSync.sync(contactsTakenIn = true)

            assertNull(waited.failure)
            assertNull(acknowledged[GROUP])
            assertTrue(ownerKeys.isEmpty())

            // As the next resend hears 409, see GroupMembershipSender.
            bob.applyPendingCommitIn(mlsGroupId = bobGroupId, groupUuid = GROUP)
            pendingChanges -= GROUP

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertContentEquals(aliceKey, ownerKeys[ALICE])
            assertEquals(listOf("post"), acknowledged[GROUP]?.toList())
        }

    @Test
    fun `a Welcome not taken in yet holds pruning back until the sync joins its group`() = runBlocking<Unit> {
        invite(creator = alice)

        assertFalse(groupSync.hasNoWelcomeWaiting())

        groupSync.sync(contactsTakenIn = true)

        assertTrue(groupSync.hasNoWelcomeWaiting())
    }

    @Test
    fun `a Welcome the sync declined holds pruning back no longer`() = runBlocking<Unit> {
        invite(creator = mallory)

        assertFalse(groupSync.hasNoWelcomeWaiting())

        groupSync.sync(contactsTakenIn = true)

        assertTrue(groupSync.hasNoWelcomeWaiting())
    }

    /** Still waiting there, it is never to be joined. */
    @Test
    fun `a Welcome declined in a group not left yet holds pruning back no longer`() = runBlocking<Unit> {
        invite(creator = mallory)
        leaveAnswer = GroupLeaveAnswer.Failed(error = NetworkError.SERVER_ERROR)

        groupSync.sync(contactsTakenIn = true)

        assertTrue(GROUP in waiting)
        assertTrue(groupSync.hasNoWelcomeWaiting())
    }

    @Test
    fun `pruning waits when the server cannot be asked`() = runBlocking<Unit> {
        isServerReachable = false

        assertFalse(groupSync.hasNoWelcomeWaiting())
    }

    /**
     * Has [creator] create the group [GROUP] with [invitees], handing [welcomePayload] in the
     * Welcome, its fields signed by [signer], [creator] unless a test says otherwise, as [revision]
     * and sealed under [groupKey] for the group [sealedFor], the key of the epoch the creation leaves
     * the group at unless a test says otherwise, and the server list it with [listedMembers] and the
     * Welcome waiting for Bob, as sent by [creator]. The Welcome is sealed to Bob's last-resort key
     * package when [sealedToLastResort], to a one-time one otherwise. Returns the key the fields are
     * sealed under.
     */
    private fun invite(
        creator: MlsClient,
        invitees: List<MlsClient> = listOf(bob, carol),
        groupKey: ByteArray? = null,
        welcomePayload: ByteArray? = welcomePayloadOf(profileKeys = emptyMap()),
        sealedFor: String = GROUP,
        listedMembers: List<String> = (listOf(creator) + invitees).map { it.userUuid() },
        sealedToLastResort: Boolean = false,
        signer: MlsClient = creator,
        revision: Long = FIRST_GROUP_REVISION,
    ): ByteArray {
        val creation = creator.createGroup(
            groupUuid = GROUP,
            invitees = invitees.map { it.inviteeKeyPackage(isLastResort = sealedToLastResort && it == bob) },
            welcomePayload = welcomePayload,
        )
        val sealingKey = groupKey ?: creator.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)

        list(
            owner = creator.userUuid(),
            groupKey = sealingKey,
            sealedFor = sealedFor,
            members = listedMembers,
            signer = signer,
            revision = revision,
        )

        welcome = creation.welcome
        mlsGroupId = creation.mlsGroupId
        inviterUuid = creator.userUuid()

        waiting[GROUP] = mutableListOf(
            GroupMessageDto(
                uuid = welcomeOf(GROUP),
                senderUserUuid = creator.userUuid(),
                ciphertext = Base64.Default.encode(creation.welcome),
                createdAt = "2026-10-23T12:00:00Z",
                departedMemberUuids = emptyList(),
            ),
        )

        return sealingKey
    }

    /**
     * Has the server list [groupUuid], owned by [owner], with [members], its [fields] as [revision],
     * signed by [signer], [owner] unless a test says otherwise, and sealed under [groupKey], of the
     * generation [informationKeyGeneration], for the group [sealedFor], the currency under
     * [currencyKey], at the epoch [keyGeneration], and whatever copy of its key it holds for Bob, see
     * [ServerKeyCopies].
     */
    private fun list(
        owner: String,
        groupKey: ByteArray,
        groupUuid: String = GROUP,
        sealedFor: String = groupUuid,
        members: List<String> = listOf(owner, BOB, CAROL),
        fields: GroupFields = FIELDS,
        signer: MlsClient = clientOf(owner),
        revision: Long = FIRST_GROUP_REVISION,
        currencyKey: ByteArray = groupKey,
        informationKeyGeneration: Long = FIRST_KEY_GENERATION,
        keyGeneration: Long = informationKeyGeneration,
    ) {
        listed += listedGroupOf(
            uuid = groupUuid,
            ownerUuid = owner,
            version = fields.sealedAs(
                creator = signer,
                groupKey = groupKey,
                groupUuid = sealedFor,
                revision = revision,
                currencyKey = currencyKey,
            ),
            memberUuids = members,
            keyGeneration = keyGeneration,
            informationKeyGeneration = informationKeyGeneration,
        )
    }

    private fun clientOf(userUuid: String) = when (userUuid) {
        ALICE -> alice
        BOB -> bob
        CAROL -> carol
        DAVE -> dave
        MALLORY -> mallory
        else -> error("No client for $userUuid")
    }

    /**
     * Has Alice create [GROUP] with Bob, Carol and Dave, whom Bob pinned as a contact reached over
     * MLS, as [invite] does, its Welcome sealed to Bob's last-resort key package when
     * [sealedToLastResort], and returns its key.
     */
    private fun inviteWithDave(sealedToLastResort: Boolean = false): ByteArray {
        bob.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())

        return invite(creator = alice, invitees = listOf(bob, carol, dave), sealedToLastResort = sealedToLastResort)
    }

    /**
     * Has Dave post the Welcome [inviteWithDave] had created again, as the group [OTHER_GROUP] of
     * his own with the same members, its fields sealed for it under [groupKey], the key the Welcome
     * handed him.
     */
    private fun postAgain(groupKey: ByteArray) {
        list(
            owner = DAVE,
            groupKey = groupKey,
            groupUuid = OTHER_GROUP,
            members = listOf(DAVE, ALICE, BOB, CAROL),
            fields = DAVES_FIELDS,
        )

        waiting[OTHER_GROUP] = mutableListOf(
            GroupMessageDto(
                uuid = welcomeOf(OTHER_GROUP),
                senderUserUuid = DAVE,
                ciphertext = Base64.Default.encode(assertNotNull(welcome)),
                createdAt = "2026-10-23T12:05:00Z",
                departedMemberUuids = emptyList(),
            ),
        )
    }

    /** What Bob is told of joining the group [inviteWithDave] had Alice create. */
    private fun aliceAddedBob() =
        GroupArrival(groupUuid = GROUP, title = "Ski trip", adderUuid = ALICE, adderFullName = "Alice")

    /**
     * That Bob holds the group [inviteWithDave] had Alice create as [GROUP] alone, with its own
     * fields, [groupKey] and members, and declined and left [OTHER_GROUP], which [postAgain] listed
     * it under, acknowledging nothing there.
     */
    private suspend fun assertHeldUnderItsOwnGroupAlone(groupKey: ByteArray) {
        assertEquals(setOf(GROUP), groups.keys)

        val group = groups.getValue(GROUP)

        assertEquals("Ski trip", group.title)
        assertEquals(ALICE, group.ownerUuid)
        assertContentEquals(groupKey, group.groupKey)
        assertTrue(bob.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), members.getValue(GROUP).mapTo(HashSet()) { it.userUuid })

        assertEquals(listOf(OTHER_GROUP), left)
        assertTrue(declined.isEmpty())
        assertNull(acknowledged[OTHER_GROUP])
    }

    /**
     * Has Bob create [GROUP] with Alice, saved with its creation as [GroupCreator] saves it, which
     * the server has not been heard to take, its key not backed up until it is, and returns its MLS
     * group. The server reads nothing of the creation here, so its nonce need not be the one
     * deriving [GROUP].
     */
    private fun createPending(): ByteArray {
        val groupKey = generateSymmetricEncryptionKey()
        val creation = bob.createGroup(
            groupUuid = GROUP,
            invitees = listOf(alice.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
        )
        val sealed = FIELDS.sealedAs(creator = bob, groupKey = groupKey, groupUuid = GROUP)

        groups[GROUP] = GroupEntity(
            uuid = GROUP,
            title = FIELDS.title,
            description = FIELDS.description,
            currency = FIELDS.currency,
            infoRevision = FIRST_GROUP_REVISION,
            ownerUuid = BOB,
            mlsGroupId = creation.mlsGroupId,
            groupKey = groupKey,
            keyGeneration = FIRST_KEY_GENERATION,
            isBackedUp = false,
            isLeft = false,
            handedProfileKeyHash = null,
        )

        pending[GROUP] = pendingCreationOf(
            groupUuid = GROUP,
            body = CreateGroupBody(
                nonce = Base64.Default.encode(ByteArray(16)),
                title = sealed.fields.title,
                description = sealed.fields.description,
                currency = sealed.fields.currency,
                signature = sealed.signature,
                welcomeMessage = Base64.Default.encode(creation.welcome),
                invitedMemberUuids = listOf(ALICE),
                keyCopy = server.copyOf(groupKey = groupKey, sealedFor = GROUP),
            ),
        )

        return creation.mlsGroupId
    }

    /**
     * Has Bob create [GROUP] with Alice, saved as [GroupCreator] saves it once the server took it,
     * and Alice join it from its Welcome, and returns her MLS group.
     */
    private fun createdWithAlice(): ByteArray {
        val groupKey = generateSymmetricEncryptionKey()
        val creation = bob.createGroup(
            groupUuid = GROUP,
            invitees = listOf(alice.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
        )

        groups[GROUP] = GroupEntity(
            uuid = GROUP,
            title = FIELDS.title,
            description = FIELDS.description,
            currency = FIELDS.currency,
            infoRevision = FIRST_GROUP_REVISION,
            ownerUuid = BOB,
            mlsGroupId = creation.mlsGroupId,
            groupKey = groupKey,
            keyGeneration = FIRST_KEY_GENERATION,
            isBackedUp = true,
            isLeft = false,
            handedProfileKeyHash = null,
        )
        members[GROUP] = listOf(
            GroupMemberEntity(userUuid = BOB, groupUuid = GROUP, isAdmin = true),
            GroupMemberEntity(userUuid = ALICE, groupUuid = GROUP, isAdmin = false),
        )

        return alice.joinGroup(
            welcome = creation.welcome,
            expectedGroupUuid = GROUP,
            expectedMembers = listOf(BOB),
            pinMembers = false,
        )
    }

    private fun welcomeOf(groupUuid: String) = "welcome-$groupUuid"

    /** A message waiting for Bob in [GROUP] that is no way in, as a Commit of an earlier membership of his is. */
    private fun unreadable(uuid: String) = GroupMessageDto(
        uuid = uuid,
        senderUserUuid = ALICE,
        ciphertext = Base64.Default.encode(ByteArray(64) { 5 }),
        createdAt = "2026-10-23T11:00:00Z",
        departedMemberUuids = emptyList(),
    )

    /**
     * Bob's sync of his direct messages, with [message] waiting for him from Alice naming the group
     * [invite] had her create, his groups those this sync saved, and what is offered to its
     * handlers noted in [offered].
     */
    private fun directMessageSync(message: ByteArray, offered: MutableList<String>) = DirectMessageSync(
        restoreContacts = { Result.Success(Unit) },
        listMessages = {
            Result.Success(
                ListDirectMessagesResponse(
                    messages = listOf(
                        DirectMessageDto(
                            uuid = "m1",
                            senderUserUuid = ALICE,
                            ciphertext = DirectMessageEnvelope(
                                mlsGroupId = assertNotNull(mlsGroupId),
                                message = message,
                            ).serialize(),
                            createdAt = "2026-10-23T12:01:00Z",
                        ),
                    ),
                ),
            )
        },
        acknowledge = { Result.Success(Unit) },
        client = { Result.Success(bob) },
        isBlocked = { false },
        isGroup = { groupId -> groups.values.any { it.mlsGroupId?.contentEquals(groupId) == true } },
        completeAcceptanceIn = { _, _, _ -> Result.Success(Unit) },
        isKnownPeer = { true },
        adopt = { _, _, _ -> },
        noteReadIn = { _, _, _ -> },
        claimReply = { _, _ -> false },
        replyIn = { _, _, _ -> },
        handlers = listOf(
            object : DirectMessageHandler {
                override suspend fun handle(
                    senderUserUuid: String,
                    mlsGroupId: ByteArray,
                    payload: ByteArray,
                ): DirectMessageHandling {
                    offered += payload.decodeToString()

                    return DirectMessageHandling.HANDLED
                }
            },
        ),
    )

    /**
     * Whether Bob can still join [GROUP] from its Welcome: nothing joined it before, which a group
     * joined and deleted again would refuse.
     */
    private fun isWelcomeUnspent(): Boolean = runCatching {
        bob.joinGroup(
            welcome = assertNotNull(welcome),
            expectedGroupUuid = GROUP,
            expectedMembers = listOf(assertNotNull(inviterUuid), CAROL),
            pinMembers = false,
        )
    }.isSuccess

    /** Whether Bob's store holds no group at all. */
    private fun holdsNoGroup() = bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty()

    /** Everything Bob's store holds, to tell that a sync left it as it was. */
    private fun mlsState(): Map<String, String> = MlsNamespace.entries
        .flatMap { namespace ->
            bobStore.keys(namespace_ = namespace).map { key ->
                val value = assertNotNull(bobStore.get(namespace_ = namespace, key = key))

                "$namespace:${Base64.Default.encode(key)}" to Base64.Default.encode(value)
            }
        }
        .toMap()

    /**
     * That a sync joins [GROUP], refuses it, deletes it again and declines it for good, see
     * [assertNothingJoined]: what it lists opens to nothing its creator signed under the keys the
     * group gives, which only joining it tells.
     */
    private suspend fun assertRefusedOnceJoined() {
        assertNothingJoined(groupSync.sync(contactsTakenIn = true))
        assertTrue(bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty())
    }

    /**
     * That a sync declines [GROUP] for good, see [assertNothingJoined], and leaves Bob's store as it
     * was: refused before it was joined, the group it was written for left joinable.
     */
    private suspend fun assertDeclinedStoringNothing() {
        val before = mlsState()

        assertNothingJoined(groupSync.sync(contactsTakenIn = true))
        assertEquals(before, mlsState())
    }

    /**
     * That [pass] failed on storage and left [GROUP] as it was: neither joined nor declined, its
     * Welcome waiting.
     */
    private fun assertLeftWaitingOnStorage(pass: GroupSyncPass) {
        assertEquals(GroupSyncError.Storage, pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertTrue(groups.isEmpty())
        assertTrue(declined.isEmpty())
        assertNull(acknowledged[GROUP])
    }

    /** That [pass] joined [GROUP] at last, its Welcome acknowledged. */
    private fun assertJoinedAtLast(pass: GroupSyncPass) {
        assertNull(pass.failure)
        assertEquals(listOf(GROUP), pass.joined.map { it.groupUuid })
        assertEquals(listOf(welcomeOf(GROUP)), acknowledged[GROUP]?.toList())
        assertTrue(declined.isEmpty())
    }

    /**
     * That [pass] joined nothing, and declined [GROUP] and left it, forgetting it was declined once
     * the server took the leave: nothing there is acknowledged, the leave dropping all of it, and
     * the server lists the group no more.
     */
    private fun assertNothingJoined(pass: GroupSyncPass) {
        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertTrue(groups.isEmpty())
        assertTrue(members.isEmpty())
        assertTrue(server.putUp.isEmpty())
        assertNull(acknowledged[GROUP])
        assertEquals(listOf(GROUP), left)
        assertTrue(declined.isEmpty())
        assertTrue(listed.none { it.uuid == GROUP })
    }
}

/** [this] once the user's own profile key the group holds for them is recorded as [handedProfileKeyHash]. */
private fun GroupEntity.withHanded(handedProfileKeyHash: String?) = GroupEntity(
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
