package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import message.ResendsBooked
import mls.ConsumeKeyPackageDto
import mls.InMemoryMlsStore
import mls.applyPendingCommitIn
import mls.discardPendingCommitIn
import mls.inviteeKeyPackage
import mls.removeMembersFrom
import mls.rosterOf
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
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

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What becomes of the changes Alice makes to the members of the group she created with Bob and
 * Carol, whatever the server answers, and what the members make of them: with every MLS client
 * made through the library itself, Bob and Carol joined from the Welcome Alice built, as
 * [GroupCreator] builds it, and the database and the server stood in for. Dave is a contact of
 * Alice's she pinned, to be added.
 */
class GroupMembershipChangerTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

    /** The key the group's fields are sealed under before anybody is removed. */
    private val groupKey = generateSymmetricEncryptionKey()

    private val creation = alice.createGroup(
        groupUuid = GROUP,
        invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage()),
        welcomePayload = groupKey,
    )

    private val bobGroupId = bob.joinGroup(
        welcome = creation.welcome,
        expectedGroupUuid = GROUP,
        expectedMembers = listOf(ALICE, CAROL),
        pinMembers = false,
    )

    private val carolGroupId = carol.joinGroup(
        welcome = creation.welcome,
        expectedGroupUuid = GROUP,
        expectedMembers = listOf(ALICE, BOB),
        pinMembers = false,
    )

    /** The user UUID the session names, Alice's unless a test says otherwise. */
    private var sessionUuid: String? = ALICE

    /** Who Alice's contacts are, see `ContactDao.getUserUuids`. */
    private var contacts = listOf(BOB, CAROL, DAVE)

    /** Who the vault blocks, see `IdentityConflictDao.getBlocked`. */
    private var blocked = emptyList<String>()

    /** What the server hands out for a key package of each user, theirs unless a test says otherwise. */
    private val keyPackages = mutableMapOf<String, Result<ConsumeKeyPackageDto, NetworkError>>()

    /** Whose key package was asked for. */
    private val fetched = mutableListOf<String>()

    private val groups = mutableMapOf(
        GROUP to GroupEntity(
            uuid = GROUP,
            title = FIELDS.title,
            description = FIELDS.description,
            currency = FIELDS.currency,
            ownerUuid = ALICE,
            mlsGroupId = creation.mlsGroupId,
            groupKey = groupKey,
            isBackedUp = true,
            isLeft = false,
            handedProfileKeyHash = null,
        ),
    )

    private val members = mutableMapOf(GROUP to rowsOf(ALICE, BOB, CAROL))
    private val pending = mutableMapOf<String, PendingMembershipChangeEntity>()
    private val pendingCreations = mutableSetOf<String>()

    /** The keys the vault holds, by group. */
    private val backedUp = mutableMapOf<String, ByteArray>()

    /**
     * The key to Alice's own profile, the one each contact of hers handed her, and those her device
     * learned through the groups.
     */
    private val aliceProfileKey = generateSymmetricEncryptionKey()

    /** Whether the vault answers for Alice's own key, see `ProfileKeyVault.getOrCreateProfileEncryptionKey`. */
    private var isVaultAnswering = true
    private val contactProfileKeys = mutableMapOf(
        BOB to generateSymmetricEncryptionKey(),
        CAROL to generateSymmetricEncryptionKey(),
        DAVE to generateSymmetricEncryptionKey(),
    )
    private val learnedProfileKeys = mutableMapOf<String, ByteArray>()

    /** Whom the server's refusals named as having deleted their account. */
    private val deletedAccounts = mutableListOf<String>()

    /** The group's fields as the server holds them, sealed under the key it was last handed. */
    private var serverFields = checkNotNull(FIELDS.seal(groupKey = groupKey, groupUuid = GROUP))

    /** Whether the server can be asked for the groups it lists. */
    private var isListingReachable = true

    /** What the server answers each kind of change. */
    private var addAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made
    private var removeAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made
    private var rotateAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made

    /** The changes that reached the server, of each kind. */
    private val additions = mutableListOf<AddGroupMembersBody>()
    private val removals = mutableListOf<RemoveGroupMembersBody>()
    private val rotations = mutableListOf<RotateGroupKeyBody>()

    /** What was saved as pending, and the resends booked, by the time each change reached the server. */
    private val pendingWhenPosted = mutableListOf<MembershipChangeKind?>()
    private val bookedWhenPosted = mutableListOf<List<Long>>()

    /** What the server waits for before it answers, when it does. */
    private var serverGate: CompletableDeferred<Unit>? = null

    /** What runs as a change is saved, and as a rotated key is taken: where the process dies, in a test. */
    private var onSavePending: (PendingMembershipChangeEntity) -> Unit = {}
    private var onAdoptKey: () -> Unit = {}

    private val resends = ResendsBooked()

    private val sender = GroupMembershipSender(
        addMembers = { _, body ->
            posted()
            additions += body
            serverGate?.await()

            addAnswer
        },
        removeMembers = { _, body ->
            posted()
            removals += body

            removeAnswer
        },
        rotateKey = { _, body ->
            posted()
            rotations += body

            if (rotateAnswer == GroupMembershipAnswer.Made) {
                serverFields =
                    SealedGroupFields(title = body.title, description = body.description, currency = body.currency)
            }

            rotateAnswer
        },
        listGroups = {
            if (isListingReachable) Result.Success(listOf(listedGroup())) else Result.Error(NetworkError.NO_INTERNET)
        },
        group = { groups[it] },
        listPending = { pending.values.toList() },
        savePending = ::save,
        dropPending = { pending.remove(it) },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        adoptKey = { groupUuid, key ->
            onAdoptKey()
            groups[groupUuid] = groups.getValue(groupUuid).withKey(groupKey = key, isBackedUp = false)
            pending.remove(groupUuid)
        },
        client = { Result.Success(alice) },
        receiveAccountDeletions = { userUuids -> deletedAccounts += userUuids },
        backUp = { groupUuid, key ->
            backedUp[groupUuid] = key
            groups[groupUuid] = groups.getValue(groupUuid).withKey(groupKey = key, isBackedUp = true)
        },
        resendScheduler = resends,
    )

    private val changer = GroupMembershipChanger(
        userUuid = { sessionUuid },
        restoreContacts = { Result.Success(Unit) },
        contacts = { contacts },
        blocked = { blocked },
        client = { Result.Success(alice) },
        consumeKeyPackage = { userUuid ->
            fetched += userUuid
            keyPackages[userUuid] ?: Result.Success(keyPackageOf(clientOf(userUuid)))
        },
        group = { groups[it] },
        hasPendingChange = { it in pending || it in pendingCreations },
        savePending = ::save,
        bookResend = sender::bookResend,
        send = sender::send,
        ownProfileKey = { aliceProfileKey.takeIf { isVaultAnswering } },
        contactProfileKey = { contactProfileKeys[it] },
        learnedProfileKey = { learnedProfileKeys[it] },
        oweProfileKey = { groupUuid ->
            groups[groupUuid] = groups.getValue(groupUuid).copy(handedProfileKeyHash = null)
        },
    )

    init {
        alice.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())
    }

    @Test
    fun `an addition is saved before it is sent with its resend booked`() = runBlocking<Unit> {
        add(DAVE)

        assertEquals(listOf<MembershipChangeKind?>(MembershipChangeKind.ADDITION), pendingWhenPosted)
        assertEquals(listOf(listOf(60L)), bookedWhenPosted)
    }

    @Test
    fun `an addition the server takes is applied and its members recorded`() = runBlocking<Unit> {
        assertEquals(Result.Success(MembershipChangeOutcome.MADE), add(DAVE))

        val body = additions.single()

        assertEquals(listOf(DAVE), body.newMemberUuids)
        assertEquals(16, Base64.Default.decode(body.nonce).size)
        assertTrue(pending.isEmpty())

        val roster = aliceRoster()

        assertFalse(roster.hasPendingCommit)
        assertEquals(2L, roster.epoch)
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), roster.memberUuids.toSet())
        assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE).toSet(), members.getValue(GROUP).toSet())
    }

    /** As the group sync has them read what the server holds for them, the creator alone allowed to commit. */
    @Test
    fun `the members already in the group follow an addition from the creator`() = runBlocking<Unit> {
        add(DAVE)

        val commit = assertIs<ProcessedMessage.Commit>(bob.process(bobGroupId, additions.single().commitMessage))

        assertEquals(ALICE, commit.committerUserUuid)
        assertEquals(2uL, commit.epoch)
        assertFalse(commit.removedSelf)
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), bob.membersOf(bobGroupId))
    }

    @Test
    fun `a newcomer joins from the Welcome and opens the fields with the key it hands them`() = runBlocking<Unit> {
        add(DAVE)

        val welcome = Base64.Default.decode(additions.single().welcomeMessage)
        val inspection = dave.inspectWelcome(welcome = welcome, expectedGroupUuid = GROUP)

        assertEquals(ALICE, inspection.committerUserUuid)
        assertEquals(listOf(ALICE, BOB, CAROL), inspection.memberUuids)

        val handedKey = assertNotNull(welcomeHandoverOf(payload = assertNotNull(inspection.welcomePayload))).groupKey

        assertContentEquals(groupKey, handedKey)
        assertEquals(FIELDS.title, serverFields.unseal(groupKey = handedKey, groupUuid = GROUP)?.title)

        val daveGroupId = dave.joinGroup(
            welcome = welcome,
            expectedGroupUuid = GROUP,
            expectedMembers = inspection.memberUuids,
            pinMembers = false,
        )

        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), dave.membersOf(daveGroupId))
    }

    @Test
    fun `the Welcome hands the newcomer the key to every member's profile`() = runBlocking<Unit> {
        add(DAVE)

        val welcome = Base64.Default.decode(additions.single().welcomeMessage)
        val payload = assertNotNull(dave.inspectWelcome(welcome = welcome, expectedGroupUuid = GROUP).welcomePayload)

        assertEveryMemberHandedOver(assertNotNull(welcomeHandoverOf(payload = payload)).profileKeys)
    }

    /** The server lists it ahead of the Commit, in the epoch the members are still in. */
    @Test
    fun `the members already in the group are handed the same keys ahead of the Commit`() = runBlocking<Unit> {
        add(DAVE)

        val body = additions.single()
        val handedOver = assertIs<ProcessedMessage.ApplicationMessage>(bob.process(bobGroupId, body.profileKeysMessage))

        assertEquals(ALICE, handedOver.senderUserUuid)
        assertEveryMemberHandedOver(assertNotNull(profileKeysOf(payload = handedOver.payload)))

        assertIs<ProcessedMessage.Commit>(bob.process(bobGroupId, body.commitMessage))
    }

    /** Read in the epoch it was sent in, which the library keeps once past it. */
    @Test
    fun `the members already in the group read the keys listed behind the Commit as well`() = runBlocking<Unit> {
        add(DAVE)

        val body = additions.single()

        assertIs<ProcessedMessage.Commit>(carol.process(carolGroupId, body.commitMessage))

        val handedOver = assertIs<ProcessedMessage.ApplicationMessage>(
            carol.process(carolGroupId, body.profileKeysMessage),
        )

        assertEveryMemberHandedOver(assertNotNull(profileKeysOf(payload = handedOver.payload)))
    }

    /** The group holds her key from her own entry, which goes on naming her to everybody already in it. */
    @Test
    fun `an addition handing over the creator's own key leaves the group holding it`() = runBlocking<Unit> {
        val handedHash = profileEncryptionKeyPartialHash(aliceProfileKey)
        groups[GROUP] = groups.getValue(GROUP).copy(handedProfileKeyHash = handedHash)

        add(DAVE)

        assertEquals(handedHash, groups.getValue(GROUP).handedProfileKeyHash)
    }

    /**
     * The vault not answering, nothing hands Alice's key over: whatever the members held for her
     * stays, and Dave holds none from the group, so her post is owed, once the addition is settled.
     */
    @Test
    fun `an addition with no key of the creator's own to hand over leaves the group owed hers`() =
        runBlocking<Unit> {
            groups[GROUP] = groups.getValue(GROUP).copy(
                handedProfileKeyHash = profileEncryptionKeyPartialHash(aliceProfileKey),
            )
            isVaultAnswering = false

            assertEquals(Result.Success(MembershipChangeOutcome.MADE), add(DAVE))

            val welcome = Base64.Default.decode(additions.single().welcomeMessage)
            val inspection = dave.inspectWelcome(welcome = welcome, expectedGroupUuid = GROUP)
            val handedOver = assertNotNull(welcomeHandoverOf(payload = assertNotNull(inspection.welcomePayload)))

            assertEquals(setOf(BOB, CAROL, DAVE), handedOver.profileKeys.keys)
            assertNull(groups.getValue(GROUP).handedProfileKeyHash)
        }

    /** Carol is no longer Alice's contact, and nothing handed Alice Bob's key in any way. */
    @Test
    fun `a member no longer a contact is handed the key learned through the groups and one with none left out`() =
        runBlocking<Unit> {
            val learned = generateSymmetricEncryptionKey()

            contactProfileKeys.remove(CAROL)
            contactProfileKeys.remove(BOB)
            learnedProfileKeys[CAROL] = learned

            add(DAVE)

            val welcome = Base64.Default.decode(additions.single().welcomeMessage)
            val inspection = dave.inspectWelcome(welcome = welcome, expectedGroupUuid = GROUP)
            val payload = assertNotNull(inspection.welcomePayload)
            val handedOver = assertNotNull(welcomeHandoverOf(payload = payload)).profileKeys

            assertEquals(setOf(ALICE, CAROL, DAVE), handedOver.keys)
            assertContentEquals(learned, handedOver[CAROL])
        }

    /** An earlier send of the same addition was stored, and its answer lost. */
    @Test
    fun `a 409 to an addition counts as made`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), add(DAVE))

        assertTrue(pending.isEmpty())
        assertEquals(2L, aliceRoster().epoch)
        assertTrue(DAVE in memberUuids())
    }

    @Test
    fun `a 410 to an addition discards its Commit and names who deleted their account`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.DeletedMembers(userUuids = listOf(DAVE))

        assertEquals(Result.Error(GroupMembershipError.Deleted(userUuids = setOf(DAVE))), add(DAVE))

        assertEquals(listOf(DAVE), deletedAccounts)
        assertNothingChanged()
    }

    @Test
    fun `an addition the server refuses discards its Commit`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)

        assertEquals(Result.Error(GroupMembershipError.Network(NetworkError.BAD_REQUEST)), add(DAVE))

        assertNothingChanged()
    }

    @Test
    fun `a 429 to an addition keeps it pending and books a resend after its Retry-After`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 42)

        assertEquals(Result.Success(MembershipChangeOutcome.PENDING), add(DAVE))

        assertEquals(MembershipChangeKind.ADDITION, pending[GROUP]?.kind)
        assertTrue(aliceRoster().hasPendingCommit)
        assertEquals(listOf(60L, 42L), resends.seconds)
        assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP))
    }

    @Test
    fun `an addition never answered stays pending and is applied once a resend hears 409`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        assertEquals(Result.Success(MembershipChangeOutcome.PENDING), add(DAVE))

        assertTrue(aliceRoster().hasPendingCommit)
        assertEquals(listOf(60L, 60L), resends.seconds)

        addAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(2, additions.size)
        assertEquals(additions[0], additions[1])
        assertTrue(pending.isEmpty())
        assertEquals(2L, aliceRoster().epoch)
        assertTrue(DAVE in memberUuids())
    }

    @Test
    fun `a resend the server does not answer either fails the sync and stays pending`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        add(DAVE)

        addAnswer = GroupMembershipAnswer.Failed(error = NetworkError.SERVER_ERROR)

        assertEquals(Result.Error(GroupSyncError.Network(NetworkError.SERVER_ERROR)), sender.resendPending())

        assertEquals(MembershipChangeKind.ADDITION, pending[GROUP]?.kind)
        assertTrue(aliceRoster().hasPendingCommit)
    }

    /** A change asked for twice is made once. */
    @Test
    fun `somebody already in the group is not added again`() = runBlocking<Unit> {
        assertEquals(Result.Success(MembershipChangeOutcome.MADE), add(BOB))

        assertTrue(fetched.isEmpty())
        assertTrue(additions.isEmpty())
        assertEquals(1L, aliceRoster().epoch)
    }

    /** The picker never selects past the limit, which the group's own tree is counted against. */
    @Test
    fun `more members than a group holds are refused before any key package is asked for`() = runBlocking<Unit> {
        assertEquals(Result.Error(GroupMembershipError.TooManyMembers), add(strangers(count = 98)))

        assertTrue(fetched.isEmpty())

        assertEquals(
            Result.Error(GroupMembershipError.NotContacts(userUuids = strangers(count = 97))),
            add(strangers(count = 97)),
        )
    }

    @Test
    fun `newcomers who are not contacts are named before any key package is asked for`() = runBlocking<Unit> {
        contacts = listOf(BOB, CAROL)

        assertEquals(Result.Error(GroupMembershipError.NotContacts(userUuids = setOf(DAVE))), add(DAVE))

        assertTrue(fetched.isEmpty())
        assertNothingChanged()
    }

    @Test
    fun `a blocked contact is named as an identity that changed`() = runBlocking<Unit> {
        blocked = listOf(DAVE)

        assertEquals(Result.Error(GroupMembershipError.IdentityChanged(userUuids = setOf(DAVE))), add(DAVE))

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `newcomers with no key package are named and nothing is built`() = runBlocking<Unit> {
        keyPackages[DAVE] = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(Result.Error(GroupMembershipError.Unreachable(userUuids = setOf(DAVE))), add(DAVE))

        assertNothingChanged()
    }

    /** As a server handing over a key package of another identity for Dave would leave Alice. */
    @Test
    fun `an identity that changed names the newcomer and builds nothing`() = runBlocking<Unit> {
        val impostor = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 5 }, store = InMemoryMlsStore())

        keyPackages[DAVE] = Result.Success(keyPackageOf(impostor))

        assertEquals(Result.Error(GroupMembershipError.IdentityChanged(userUuids = setOf(DAVE))), add(DAVE))

        assertNothingChanged()
    }

    @Test
    fun `an addition that could not be saved has its Commit discarded and is never sent`() = runBlocking<Unit> {
        onSavePending = { throw IllegalStateException("disk full") }

        assertEquals(Result.Error(GroupMembershipError.Storage), add(DAVE))

        assertTrue(additions.isEmpty())
        assertNothingChanged()
    }

    @Test
    fun `a removal the server takes is followed by a key rotation`() = runBlocking<Unit> {
        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        assertEquals(listOf(CAROL), removals.single().removedMemberUuids)
        assertEquals(rowsOf(ALICE, BOB).toSet(), members.getValue(GROUP).toSet())
        assertEquals(setOf(ALICE, BOB), aliceRoster().memberUuids.toSet())
        assertTrue(pending.isEmpty())

        val rotated = groups.getValue(GROUP).groupKey

        assertFalse(rotated.contentEquals(groupKey))
        assertContentEquals(rotated, backedUp[GROUP])
        assertTrue(groups.getValue(GROUP).isBackedUp)

        // Re-sealed under the new key alone, as they were.
        val fields = assertNotNull(serverFields.unseal(groupKey = rotated, groupUuid = GROUP))

        assertEquals(FIELDS.title, fields.title)
        assertEquals(FIELDS.description, fields.description)
        assertEquals(FIELDS.currency, fields.currency)
        assertNull(serverFields.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `the members who remain read the new key from the creator after the removal`() = runBlocking<Unit> {
        remove(CAROL)

        val commit = assertIs<ProcessedMessage.Commit>(bob.process(bobGroupId, removals.single().commitMessage))

        assertEquals(ALICE, commit.committerUserUuid)
        assertFalse(commit.removedSelf)
        assertEquals(setOf(ALICE, BOB), bob.membersOf(bobGroupId))

        val keyMessage = assertIs<ProcessedMessage.ApplicationMessage>(
            bob.process(bobGroupId, rotations.single().keyMessage),
        )

        assertEquals(ALICE, keyMessage.senderUserUuid)
        assertContentEquals(groups.getValue(GROUP).groupKey, groupKeyOf(keyMessage.payload))
    }

    @Test
    fun `a removed member finds out from the Commit and cannot read the new key`() = runBlocking<Unit> {
        remove(CAROL)

        val commit = assertIs<ProcessedMessage.Commit>(carol.process(carolGroupId, removals.single().commitMessage))

        assertEquals(ALICE, commit.committerUserUuid)
        assertTrue(commit.removedSelf)

        assertFailsWith<MlsException> { carol.process(carolGroupId, rotations.single().keyMessage) }
    }

    /** What the user edited of the group is edited on this device alone. */
    @Test
    fun `a key rotation re-seals the fields the server holds and not those edited here`() = runBlocking<Unit> {
        groups[GROUP] = groups.getValue(GROUP).withTitle("Edited here")

        remove(CAROL)

        val body = rotations.single()
        val sealed = SealedGroupFields(title = body.title, description = body.description, currency = body.currency)

        assertEquals(FIELDS.title, sealed.unseal(groupKey = groups.getValue(GROUP).groupKey, groupUuid = GROUP)?.title)
    }

    @Test
    fun `a 409 to a removal counts as made and rotates the key`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        assertEquals(1, rotations.size)
        assertTrue(pending.isEmpty())
        assertFalse(groups.getValue(GROUP).groupKey.contentEquals(groupKey))
    }

    @Test
    fun `a removal the server refuses discards its Commit and rotates nothing`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.Refused(error = NetworkError.UNPROCESSABLE_ENTITY)

        assertEquals(Result.Error(GroupMembershipError.Network(NetworkError.UNPROCESSABLE_ENTITY)), remove(CAROL))

        assertTrue(rotations.isEmpty())
        assertNothingChanged()
    }

    @Test
    fun `a 429 to a removal keeps it pending and rotates nothing yet`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 42)

        assertEquals(Result.Success(MembershipChangeOutcome.PENDING), remove(CAROL))

        assertEquals(MembershipChangeKind.REMOVAL, pending[GROUP]?.kind)
        assertTrue(aliceRoster().hasPendingCommit)
        assertTrue(rotations.isEmpty())
        assertEquals(listOf(60L, 42L), resends.seconds)
        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
    }

    @Test
    fun `a removal never answered is rotated once a resend hears 409`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.Failed(error = NetworkError.NO_INTERNET)

        assertEquals(Result.Success(MembershipChangeOutcome.PENDING), remove(CAROL))

        assertTrue(rotations.isEmpty())

        removeAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(2, removals.size)
        assertEquals(removals[0], removals[1])
        assertEquals(1, rotations.size)
        assertTrue(pending.isEmpty())
        assertEquals(rowsOf(ALICE, BOB).toSet(), members.getValue(GROUP).toSet())
        assertFalse(groups.getValue(GROUP).groupKey.contentEquals(groupKey))
    }

    @Test
    fun `somebody no longer in the group is not removed again`() = runBlocking<Unit> {
        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(DAVE))

        assertTrue(removals.isEmpty())
        assertEquals(1L, aliceRoster().epoch)
    }

    /** The removal is made all the same, and the group keeps the key it had. */
    @Test
    fun `a key rotation the server refuses leaves the group its key`() = runBlocking<Unit> {
        rotateAnswer = GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        assertTrue(pending.isEmpty())
        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        assertEquals(rowsOf(ALICE, BOB).toSet(), members.getValue(GROUP).toSet())
    }

    @Test
    fun `a 429 to a key rotation keeps it pending with the group on its old key`() = runBlocking<Unit> {
        rotateAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 42)

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        val rotation = assertNotNull(pending[GROUP])

        assertEquals(MembershipChangeKind.KEY_ROTATION, rotation.kind)
        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        assertEquals(listOf(60L, 42L), resends.seconds)
    }

    @Test
    fun `a key rotation never answered is taken by a resend and its key adopted then`() = runBlocking<Unit> {
        rotateAnswer = GroupMembershipAnswer.Failed(error = NetworkError.SERVER_ERROR)

        remove(CAROL)

        val rotatedKey = assertNotNull(pending[GROUP]?.groupKey)

        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)

        rotateAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(1, removals.size)
        assertEquals(2, rotations.size)
        assertEquals(rotations[0], rotations[1])
        assertTrue(pending.isEmpty())
        assertContentEquals(rotatedKey, groups.getValue(GROUP).groupKey)
        assertContentEquals(rotatedKey, backedUp[GROUP])
    }

    /** The fields re-sealed are those the server holds, which it has to be asked for. */
    @Test
    fun `a removal whose fields could not be asked for is rotated by the next sync`() = runBlocking<Unit> {
        isListingReachable = false

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        assertEquals(MembershipChangeKind.REMOVAL, pending[GROUP]?.kind)
        assertEquals(rowsOf(ALICE, BOB).toSet(), members.getValue(GROUP).toSet())
        assertTrue(rotations.isEmpty())
        assertEquals(listOf(60L, 60L), resends.seconds)

        isListingReachable = true

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(1, removals.size)
        assertEquals(1, rotations.size)
        assertTrue(pending.isEmpty())
        assertFalse(groups.getValue(GROUP).groupKey.contentEquals(groupKey))
    }

    /** The process dies between the removal applied and its key rotation saved. */
    @Test
    fun `a removal applied whose rotation was never saved is rotated by the next sync`() = runBlocking<Unit> {
        onSavePending = { change ->
            if (change.kind == MembershipChangeKind.KEY_ROTATION) throw IllegalStateException("killed")
        }

        assertFailsWith<IllegalStateException> { remove(CAROL) }

        assertEquals(MembershipChangeKind.REMOVAL, pending[GROUP]?.kind)
        assertFalse(aliceRoster().hasPendingCommit)
        assertEquals(2L, aliceRoster().epoch)
        assertTrue(rotations.isEmpty())

        onSavePending = {}

        assertEquals(Result.Success(Unit), sender.resendPending())

        // Not sent again, nor applied twice: the group past its epoch says it was.
        assertEquals(1, removals.size)
        assertEquals(2L, aliceRoster().epoch)
        assertEquals(1, rotations.size)
        assertTrue(pending.isEmpty())

        bob.process(bobGroupId, removals.single().commitMessage)

        val keyMessage = assertIs<ProcessedMessage.ApplicationMessage>(
            bob.process(bobGroupId, rotations.single().keyMessage),
        )

        assertContentEquals(groups.getValue(GROUP).groupKey, groupKeyOf(keyMessage.payload))
    }

    /** The process dies between the server taking the key rotation and the key being taken here. */
    @Test
    fun `a key rotation taken whose key was not adopted yet is adopted by the next sync`() = runBlocking<Unit> {
        onAdoptKey = { throw IllegalStateException("killed") }

        assertFailsWith<IllegalStateException> { remove(CAROL) }

        val rotatedKey = assertNotNull(pending[GROUP]?.groupKey)

        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)

        onAdoptKey = {}
        rotateAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(Unit), sender.resendPending())

        // The same key message sent again, which the server stored once, and its key taken.
        assertEquals(2, rotations.size)
        assertEquals(rotations[0], rotations[1])
        assertTrue(pending.isEmpty())
        assertContentEquals(rotatedKey, groups.getValue(GROUP).groupKey)

        bob.process(bobGroupId, removals.single().commitMessage)

        val keyMessage = assertIs<ProcessedMessage.ApplicationMessage>(
            bob.process(bobGroupId, rotations.first().keyMessage),
        )

        assertContentEquals(rotatedKey, groupKeyOf(keyMessage.payload))
    }

    /** The process dies between the server taking the addition and its Commit applied. */
    @Test
    fun `an addition taken and applied whose change outlived it is only recorded by the next sync`() =
        runBlocking<Unit> {
            addAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 1)

            add(DAVE)

            // As the sender would have, short of dropping the change.
            alice.applyPendingCommitIn(mlsGroupId = creation.mlsGroupId)

            assertEquals(Result.Success(Unit), sender.resendPending())

            assertEquals(1, additions.size)
            assertEquals(2L, aliceRoster().epoch)
            assertTrue(pending.isEmpty())
            assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE).toSet(), members.getValue(GROUP).toSet())
        }

    /** The process dies between a refused Commit discarded and its change dropped. */
    @Test
    fun `a change whose Commit was discarded is dropped by the next sync without being sent`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 1)

        remove(CAROL)

        alice.discardPendingCommitIn(mlsGroupId = creation.mlsGroupId)

        assertEquals(Result.Success(Unit), sender.resendPending())

        // Neither sent again nor taken for a removal applied, whose key would be rotated.
        assertEquals(1, removals.size)
        assertTrue(rotations.isEmpty())
        assertNothingChanged()
    }

    @Test
    fun `a change pending refuses another until it is taken`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        add(DAVE)

        assertEquals(Result.Error(GroupMembershipError.ChangePending), remove(CAROL))
        assertEquals(Result.Error(GroupMembershipError.ChangePending), add(DAVE))

        assertEquals(1, additions.size)
        assertTrue(removals.isEmpty())

        addAnswer = GroupMembershipAnswer.AlreadyMade

        sender.resendPending()

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))
    }

    /** Its creation may not have reached the server, which then holds no group to change. */
    @Test
    fun `a group still being created refuses a change to its members`() = runBlocking<Unit> {
        pendingCreations += GROUP

        assertEquals(Result.Error(GroupMembershipError.ChangePending), add(DAVE))

        assertTrue(fetched.isEmpty())
        assertNothingChanged()
    }

    /** The process died between a Commit built and its change saved, so it never went out. */
    @Test
    fun `a Commit built and never saved is discarded before the next change is built`() = runBlocking<Unit> {
        alice.removeMembersFrom(mlsGroupId = creation.mlsGroupId, userUuids = listOf(CAROL))

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), add(DAVE))

        val commit = assertIs<ProcessedMessage.Commit>(bob.process(bobGroupId, additions.single().commitMessage))

        assertEquals(2uL, commit.epoch)
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), bob.membersOf(bobGroupId))
    }

    /** Leaving the screen does not leave the change for the next sync. */
    @Test
    fun `a change goes on to its answer once sent even when its caller goes`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()

        serverGate = gate

        val addition = launch { add(DAVE) }

        while (additions.isEmpty()) yield()

        addition.cancel()
        gate.complete(Unit)
        addition.join()

        assertTrue(pending.isEmpty())
        assertTrue(DAVE in memberUuids())
    }

    @Test
    fun `only the creator changes the members of a group`() = runBlocking<Unit> {
        groups[GROUP] = groups.getValue(GROUP).withOwner(BOB)

        assertEquals(Result.Error(GroupMembershipError.NotCreator), add(DAVE))
        assertEquals(Result.Error(GroupMembershipError.NotCreator), remove(CAROL))

        assertNothingChanged()
    }

    /** A log-out takes every MLS group with it, see `GroupSync.restore`. */
    @Test
    fun `a group brought back after a log-out cannot be changed from this device`() = runBlocking<Unit> {
        groups[GROUP] = groups.getValue(GROUP).restored()

        assertEquals(Result.Error(GroupMembershipError.NotHeld), add(DAVE))
        assertEquals(Result.Error(GroupMembershipError.NotHeld), remove(CAROL))
    }

    @Test
    fun `nobody signed in changes nothing`() = runBlocking<Unit> {
        sessionUuid = null

        assertEquals(Result.Error(GroupMembershipError.NoSession), add(DAVE))
        assertEquals(Result.Error(GroupMembershipError.NoSession), remove(CAROL))

        assertNothingChanged()
    }

    /** The creator never leaves: nobody else could change the group. */
    @Test
    fun `the creator removing themselves is a bug`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { remove(ALICE) }
    }

    private suspend fun add(vararg userUuids: String) = add(userUuids.toSet())

    private suspend fun add(userUuids: Set<String>) = changer.add(groupUuid = GROUP, userUuids = userUuids)

    private suspend fun remove(vararg userUuids: String) =
        changer.remove(groupUuid = GROUP, userUuids = userUuids.toSet())

    private fun save(change: PendingMembershipChangeEntity) {
        onSavePending(change)
        pending[change.groupUuid] = change
    }

    private fun posted() {
        pendingWhenPosted += pending[GROUP]?.kind
        bookedWhenPosted += resends.seconds.toList()
    }

    private suspend fun aliceRoster() = alice.rosterOf(mlsGroupId = creation.mlsGroupId)

    /** That [handedOver] is the key to the profile of every member once Dave is in, as Alice holds them. */
    private fun assertEveryMemberHandedOver(handedOver: Map<String, ByteArray>) {
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), handedOver.keys)
        assertContentEquals(aliceProfileKey, handedOver[ALICE])
        assertContentEquals(contactProfileKeys[BOB], handedOver[BOB])
        assertContentEquals(contactProfileKeys[CAROL], handedOver[CAROL])
        assertContentEquals(contactProfileKeys[DAVE], handedOver[DAVE])
    }

    /** The group as the server lists it. */
    private fun listedGroup() = GroupDto(
        uuid = GROUP,
        ownerUuid = ALICE,
        title = serverFields.title,
        description = serverFields.description,
        currency = serverFields.currency,
        members = members.getValue(GROUP).map { GroupMemberDto(userUuid = it.userUuid) },
    )

    private fun memberUuids() = members.getValue(GROUP).mapTo(HashSet()) { it.userUuid }

    private fun clientOf(userUuid: String) = when (userUuid) {
        BOB -> bob
        CAROL -> carol
        DAVE -> dave
        else -> error("No client for $userUuid")
    }

    private fun keyPackageOf(client: MlsClient): ConsumeKeyPackageDto {
        val keyPackage = client.generateKeyPackages(oneTimeCount = 1u).oneTimeKeyPackages.single()

        return ConsumeKeyPackageDto(keyPackage = Base64.Default.encode(keyPackage))
    }

    /** What a member's group sync makes of [message], waiting for them in the group [mlsGroupId]. */
    private fun MlsClient.process(mlsGroupId: ByteArray, message: String): ProcessedMessage =
        loadGroup(mlsGroupId = mlsGroupId).use { group ->
            group.processIncomingMessage(message = Base64.Default.decode(message), allowedCommitter = ALICE)
        }

    private fun MlsClient.membersOf(mlsGroupId: ByteArray): Set<String> =
        loadGroup(mlsGroupId = mlsGroupId).use { group -> group.members().mapTo(HashSet()) { it.userUuid } }

    /** That the group is as it was before the test, nothing pending: its members, its key and its epoch. */
    private suspend fun assertNothingChanged() {
        assertTrue(pending.isEmpty())
        assertEquals(rowsOf(ALICE, BOB, CAROL), members.getValue(GROUP))
        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)

        val roster = aliceRoster()

        assertFalse(roster.hasPendingCommit)
        assertEquals(1L, roster.epoch)
        assertEquals(setOf(ALICE, BOB, CAROL), roster.memberUuids.toSet())
    }

    private fun strangers(count: Int) =
        (1..count).mapTo(HashSet()) { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
}

/** The rows of the group's members [userUuids], Alice their admin. */
private fun rowsOf(vararg userUuids: String) =
    userUuids.map { GroupMemberEntity(userUuid = it, groupUuid = GROUP, isAdmin = it == ALICE) }

private fun GroupEntity.copy(
    title: String = this.title,
    ownerUuid: String = this.ownerUuid,
    mlsGroupId: ByteArray? = this.mlsGroupId,
    groupKey: ByteArray = this.groupKey,
    isBackedUp: Boolean = this.isBackedUp,
    handedProfileKeyHash: String? = this.handedProfileKeyHash,
) = GroupEntity(
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

/** [this] as `GroupDao.adoptRotatedKey` and `GroupDao.markBackedUp` leave it. */
private fun GroupEntity.withKey(groupKey: ByteArray, isBackedUp: Boolean) =
    copy(groupKey = groupKey, isBackedUp = isBackedUp)

/** [this] with its title edited on this device. */
private fun GroupEntity.withTitle(title: String) = copy(title = title)

private fun GroupEntity.withOwner(ownerUuid: String) = copy(ownerUuid = ownerUuid)

/** [this] as a log-out and the group sync bringing it back leave it. */
private fun GroupEntity.restored() = copy(mlsGroupId = null)
