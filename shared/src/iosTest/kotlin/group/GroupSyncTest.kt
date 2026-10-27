package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.data.remote.DirectMessageDto
import database.data.remote.ListDirectMessagesResponse
import database.domain.CurrencyCatalog
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
import mls.encryptApplicationMessage
import mls.holdsGroup
import mls.inviteeKeyPackage
import mls.joinGroupFrom
import mls.readApplicationMessage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
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
 * own creations still waiting for an answer. The database, the vault and the server are stood in
 * for.
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

    /** The groups the server lists for Bob, and whether it can be asked at all. */
    private val listed = mutableListOf<GroupDto>()
    private var isServerReachable = true

    /** What waits for Bob in each group, and the groups whose messages cannot be asked for. */
    private val waiting = mutableMapOf<String, MutableList<GroupMessageDto>>()
    private val unreachableMessages = mutableSetOf<String>()

    /** Which groups' messages were asked for, and which messages were acknowledged in each. */
    private val fetched = mutableListOf<String>()
    private val acknowledged = mutableMapOf<String, MutableList<String>>()

    private val groups = mutableMapOf<String, GroupEntity>()
    private val members = mutableMapOf<String, List<GroupMemberEntity>>()
    private val pending = mutableMapOf<String, PendingGroupCreationEntity>()
    private val declined = mutableSetOf<String>()

    /** Whether saving a group fails, as the database failing would. */
    private var isDatabaseFailing = false

    /** What runs each time the sync saves a group, once the library has joined it. */
    private var onSave: suspend () -> Unit = {}

    /**
     * What runs each time the sync asks whether somebody is an unblocked contact: past the
     * inspection of a Welcome, and before its join.
     */
    private var onContactChecked: suspend () -> Unit = {}

    /** The group keys the vault holds, and those the sync backed up. */
    private val vault = mutableMapOf<String, ByteArray>()
    private val backedUp = mutableMapOf<String, ByteArray>()

    /** The Welcome of the last group [invite] had created, and its MLS group. */
    private var welcome: ByteArray? = null
    private var mlsGroupId: ByteArray? = null

    /** What the server answers a creation sent again. */
    private var answer: GroupCreationAnswer = GroupCreationAnswer.Created

    private val resends = ResendsBooked()

    private val sender = GroupCreationSender(
        post = { answer },
        dropPending = { groupUuid -> pending.remove(groupUuid) },
        deleteRefused = { groupUuid ->
            pending.remove(groupUuid)
            members.remove(groupUuid)
            groups.remove(groupUuid)
        },
        deleteKey = { groupUuid ->
            vault.remove(groupUuid)
            Result.Success(Unit)
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
        backUpPending = { Result.Success(Unit) },
        restoreContacts = { Result.Success(Unit) },
        listGroups = {
            if (isServerReachable) Result.Success(listed.toList()) else Result.Error(NetworkError.NO_INTERNET)
        },
        storedGroups = { groups.values.toList() },
        declinedGroups = { declined.toList() },
        vaultKeys = { Result.Success(vault.toMap()) },
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
        isUnblockedContact = {
            onContactChecked()
            it in contacts && it !in blocked
        },
        client = { Result.Success(bob) },
        save = { group, groupMembers ->
            onSave()
            check(!isDatabaseFailing) { "The database is failing." }
            groups[group.uuid] = group
            members[group.uuid] = groupMembers
        },
        memberUuidsOf = { groupUuid -> members[groupUuid].orEmpty().map { it.userUuid } },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        updateKey = { _, _ -> error("No group here is handed a new key.") },
        markLeft = { error("Nobody is removed from a group here.") },
        recordDeclined = { groupUuid -> declined += groupUuid },
        backUp = { groupUuid, groupKey ->
            backedUp[groupUuid] = groupKey
            Result.Success(Unit)
        },
        fullName = { names[it] },
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

        assertContentEquals(groupKey, backedUp[GROUP])
        assertEquals(listOf(welcomeOf(GROUP)), acknowledged[GROUP]?.toList())
        assertTrue(declined.isEmpty())
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
    fun `a group a stranger created is acknowledged and never joined or asked about again`() = runBlocking<Unit> {
        invite(creator = mallory)

        assertDeclinedStoringNothing()
        assertTrue(isWelcomeUnspent())

        fetched.clear()
        groupSync.sync(contactsTakenIn = true)

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a group a blocked contact created is acknowledged and never joined or asked about again`() =
        runBlocking<Unit> {
            blocked = setOf(ALICE)
            invite(creator = alice)

            assertDeclinedStoringNothing()
            assertTrue(isWelcomeUnspent())

            fetched.clear()
            groupSync.sync(contactsTakenIn = true)

            assertTrue(fetched.isEmpty())
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

    /** Refused by the fields, which open under nothing but a 32-byte key, see [SealedGroupFields.unseal]. */
    @Test
    fun `a key that is not 32 bytes is refused before anything is joined`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()

        invite(
            creator = alice,
            groupKey = groupKey,
            welcomePayload = groupKey + 0,
        )

        assertDeclinedStoringNothing()
    }

    @Test
    fun `a Welcome handing over no key is refused before anything is joined`() = runBlocking<Unit> {
        invite(creator = alice, welcomePayload = null)

        assertDeclinedStoringNothing()
    }

    /** The server serving one group's fields under another group's UUID. */
    @Test
    fun `fields sealed for another group are refused before anything is joined`() = runBlocking<Unit> {
        invite(creator = alice, sealedFor = OTHER_GROUP)

        assertDeclinedStoringNothing()
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
            assertEquals(setOf(OTHER_GROUP), declined)
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
     * tree of the Welcome, nobody committing her removal.
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

    /** As a log-out leaves Bob: the Welcome long acknowledged, the key in the vault, no MLS state. */
    @Test
    fun `a group whose key the vault holds is brought back without a Welcome or MLS state`() = runBlocking<Unit> {
        val groupKey = generateSymmetricEncryptionKey()

        list(owner = ALICE, groupKey = groupKey)
        vault[GROUP] = groupKey

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
        assertTrue(backedUp.isEmpty())
    }

    /** As a log-out leaves every group declined before it: the Welcome acknowledged, no key in the vault. */
    @Test
    fun `a group with no Welcome waiting and no key in the vault is declined and never asked about again`() =
        runBlocking<Unit> {
            list(owner = ALICE, groupKey = generateSymmetricEncryptionKey())

            val pass = groupSync.sync(contactsTakenIn = true)

            assertNull(pass.failure)
            assertTrue(groups.isEmpty())
            assertEquals(setOf(GROUP), declined)
            assertEquals(listOf(GROUP), fetched)

            fetched.clear()
            groupSync.sync(contactsTakenIn = true)

            assertTrue(fetched.isEmpty())
        }

    @Test
    fun `a creation the server takes at the next sync keeps its group and nothing pending`() = runBlocking<Unit> {
        createPending()

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(GROUP in groups)
        assertTrue(pending.isEmpty())
        assertTrue(resends.seconds.isEmpty())
    }

    @Test
    fun `a creation the server refuses at the next sync goes with its key and its MLS group`() = runBlocking<Unit> {
        val mlsGroupId = createPending()
        answer = GroupCreationAnswer.Refused(error = NetworkError.BAD_REQUEST)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertTrue(groups.isEmpty())
        assertTrue(pending.isEmpty())
        assertTrue(vault.isEmpty())
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

    @Test
    fun `pruning waits when the server cannot be asked`() = runBlocking<Unit> {
        isServerReachable = false

        assertFalse(groupSync.hasNoWelcomeWaiting())
    }

    /**
     * Has [creator] create the group [GROUP] with [invitees], handing [welcomePayload] in the
     * Welcome, its fields sealed under [groupKey] for the group [sealedFor], and the server list it
     * with [listedMembers] and the Welcome waiting for Bob, as sent by [creator]. The Welcome is
     * sealed to Bob's last-resort key package when [sealedToLastResort], to a one-time one
     * otherwise. Returns [groupKey].
     */
    private fun invite(
        creator: MlsClient,
        invitees: List<MlsClient> = listOf(bob, carol),
        groupKey: ByteArray = generateSymmetricEncryptionKey(),
        welcomePayload: ByteArray? = groupKey,
        sealedFor: String = GROUP,
        listedMembers: List<String> = (listOf(creator) + invitees).map { it.userUuid() },
        sealedToLastResort: Boolean = false,
    ): ByteArray {
        val creation = creator.createGroup(
            groupUuid = GROUP,
            invitees = invitees.map { it.inviteeKeyPackage(isLastResort = sealedToLastResort && it == bob) },
            welcomePayload = welcomePayload,
        )

        list(owner = creator.userUuid(), groupKey = groupKey, sealedFor = sealedFor, members = listedMembers)

        welcome = creation.welcome
        mlsGroupId = creation.mlsGroupId

        waiting[GROUP] = mutableListOf(
            GroupMessageDto(
                uuid = welcomeOf(GROUP),
                senderUserUuid = creator.userUuid(),
                ciphertext = Base64.Default.encode(creation.welcome),
                createdAt = "2026-10-23T12:00:00Z",
            ),
        )

        return groupKey
    }

    /**
     * Has the server list [groupUuid], owned by [owner], with [members], its [fields] sealed under
     * [groupKey] for the group [sealedFor].
     */
    private fun list(
        owner: String,
        groupKey: ByteArray,
        groupUuid: String = GROUP,
        sealedFor: String = groupUuid,
        members: List<String> = listOf(owner, BOB, CAROL),
        fields: GroupFields = FIELDS,
    ) {
        val sealed = assertNotNull(fields.seal(groupKey = groupKey, groupUuid = sealedFor))

        listed += GroupDto(
            uuid = groupUuid,
            ownerUuid = owner,
            title = sealed.title,
            description = sealed.description,
            currency = sealed.currency,
            members = members.map { GroupMemberDto(userUuid = it) },
        )
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
            ),
        )
    }

    /** What Bob is told of joining the group [inviteWithDave] had Alice create. */
    private fun aliceAddedBob() =
        GroupArrival(groupUuid = GROUP, title = "Ski trip", adderUuid = ALICE, adderFullName = "Alice")

    /**
     * That Bob holds the group [inviteWithDave] had Alice create as [GROUP] alone, with its own
     * fields, [groupKey] and members, and declined [OTHER_GROUP] for good, which [postAgain] listed
     * it under.
     */
    private suspend fun assertHeldUnderItsOwnGroupAlone(groupKey: ByteArray) {
        assertEquals(setOf(GROUP), groups.keys)

        val group = groups.getValue(GROUP)

        assertEquals("Ski trip", group.title)
        assertEquals(ALICE, group.ownerUuid)
        assertContentEquals(groupKey, group.groupKey)
        assertTrue(bob.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), members.getValue(GROUP).mapTo(HashSet()) { it.userUuid })

        assertEquals(setOf(OTHER_GROUP), declined)
        assertEquals(listOf(welcomeOf(OTHER_GROUP)), acknowledged[OTHER_GROUP]?.toList())
    }

    /**
     * Has Bob create [GROUP] with Alice, saved with its creation as [GroupCreator] saves it, which
     * the server has not been heard to take, its key backed up, and returns its MLS group. The
     * server reads nothing of the creation here, so its nonce need not be the one deriving [GROUP].
     */
    private fun createPending(): ByteArray {
        val groupKey = generateSymmetricEncryptionKey()
        val creation = bob.createGroup(
            groupUuid = GROUP,
            invitees = listOf(alice.inviteeKeyPackage()),
            welcomePayload = groupKey,
        )
        val sealed = assertNotNull(FIELDS.seal(groupKey = groupKey, groupUuid = GROUP))

        groups[GROUP] = GroupEntity(
            uuid = GROUP,
            title = FIELDS.title,
            description = FIELDS.description,
            currency = FIELDS.currency,
            ownerUuid = BOB,
            mlsGroupId = creation.mlsGroupId,
            groupKey = groupKey,
            isBackedUp = true,
            isLeft = false,
        )

        vault[GROUP] = groupKey

        pending[GROUP] = pendingCreationOf(
            groupUuid = GROUP,
            body = CreateGroupBody(
                nonce = Base64.Default.encode(ByteArray(16)),
                title = sealed.title,
                description = sealed.description,
                currency = sealed.currency,
                welcomeMessage = Base64.Default.encode(creation.welcome),
                invitedMemberUuids = listOf(ALICE),
            ),
        )

        return creation.mlsGroupId
    }

    private fun welcomeOf(groupUuid: String) = "welcome-$groupUuid"

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
            expectedMembers = listOf(listed.single().ownerUuid, CAROL),
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

    /** That [pass] joined nothing, and declined [GROUP] for good, its Welcome acknowledged. */
    private fun assertNothingJoined(pass: GroupSyncPass) {
        assertNull(pass.failure)
        assertTrue(pass.joined.isEmpty())
        assertTrue(groups.isEmpty())
        assertTrue(members.isEmpty())
        assertTrue(backedUp.isEmpty())
        assertEquals(listOf(welcomeOf(GROUP)), acknowledged[GROUP]?.toList())
        assertEquals(setOf(GROUP), declined)
    }
}
