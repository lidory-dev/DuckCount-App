package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.remote.EncryptedFieldDto
import database.domain.CurrencyCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import uniffi.lib_duckcount.CommitPolicy
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.ProcessedMessage
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
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

private const val DEVICE_LOCKED = "KSafe: Cannot access Keystore key - device is locked"

/** What a change reads from the vault, which a locked device refuses, each on its own in a test. */
private enum class VaultRead { SESSION, IDENTITY, CONTACTS, PROFILE_KEY, KEY_RING }

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What becomes of the Commits Alice makes to the group she created with Bob and Carol, whatever
 * the server answers, and what the members make of them: with every MLS client made through the
 * library itself, Bob and Carol joined from the Welcome Alice built, as [GroupCreator] builds it,
 * and the database, the key ring and the server stood in for, the server keeping the link and the
 * copy of the next key each Commit it takes carries, see [ServerKeyCopies]. Dave is a contact of
 * Alice's she pinned, to be added.
 */
class GroupMembershipChangerTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

    private val creation = alice.createGroup(
        groupUuid = GROUP,
        invitees = listOf(bob.inviteeKeyPackage(), carol.inviteeKeyPackage()),
        welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
    )

    /** The key of the epoch the creation leaves the group at, which its fields are sealed under. */
    private val groupKey = alice.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)

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
            infoRevision = FIRST_GROUP_REVISION,
            ownerUuid = ALICE,
            mlsGroupId = creation.mlsGroupId,
            groupKey = groupKey,
            keyGeneration = FIRST_KEY_GENERATION,
            isBackedUp = true,
            isLeft = false,
            handedProfileKeyHash = null,
        ),
    )

    private val members = mutableMapOf(GROUP to rowsOf(ALICE, BOB, CAROL))
    /** The changes waiting on the server, and those to be made again, see [PendingMembershipChangeEntity.isOutdated]. */
    private val pending = mutableMapOf<String, PendingMembershipChangeEntity>()
    private val outdated = mutableMapOf<String, PendingMembershipChangeEntity>()
    private val pendingCreations = mutableSetOf<String>()

    /** Alice's copy of the group's key as the server keeps it, and the key ring it is sealed with. */
    private val server = ServerKeyCopies()
    private val keyCopies = server.groupKeyCopies(groups)

    /**
     * The key to Alice's own profile, the one each contact of hers handed her, and those her device
     * learned through the groups.
     */
    private val aliceProfileKey = generateSymmetricEncryptionKey()

    /** Whether the vault answers for Alice's own key, see `ProfileKeyVault.getOrCreateProfileEncryptionKey`. */
    private var isVaultAnswering = true

    /** The reads the device refuses, as locked, throwing as KSafe does then. */
    private val locked = mutableSetOf<VaultRead>()
    private val contactProfileKeys = mutableMapOf(
        BOB to generateSymmetricEncryptionKey(),
        CAROL to generateSymmetricEncryptionKey(),
        DAVE to generateSymmetricEncryptionKey(),
    )
    private val learnedProfileKeys = mutableMapOf<String, ByteArray>()

    /** Whom the server's refusals named as having deleted their account. */
    private val deletedAccounts = mutableListOf<String>()

    /**
     * The signature key Alice's device recorded for each member, see `GroupMemberSignatureKeyEntity`,
     * and the links back through the group's keys the server stored with each Commit it took, see
     * [GroupKeyChain].
     */
    private val signatureKeys = mutableMapOf<String, ByteArray>()
    private val serverLinks = mutableListOf<GroupKeyLinkDto>()

    /** The group's information as the server holds it, signed by Alice, sealed as she created it. */
    private val serverVersion = FIELDS.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP)

    /**
     * What the server answers each kind of change, the answers to an addition taken one after the
     * other while there are more than one.
     */
    private val addAnswers = mutableListOf<GroupMembershipAnswer>()
    private var addAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made
    private var removeAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made
    private var departedAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made
    private var replaceAnswer: GroupMembershipAnswer = GroupMembershipAnswer.Made

    /** The changes that reached the server, of each kind. */
    private val additions = mutableListOf<AddGroupMembersBody>()
    private val removals = mutableListOf<RemoveGroupMembersBody>()
    private val departedRemovals = mutableListOf<RemoveDepartedMembersBody>()
    private val replacements = mutableListOf<ReplaceGroupCommitBody>()

    /** What was saved as pending, and the resends booked, by the time each change reached the server. */
    private val pendingWhenPosted = mutableListOf<MembershipChangeKind?>()
    private val bookedWhenPosted = mutableListOf<List<Long>>()

    /** What the server waits for before it answers, when it does. */
    private var serverGate: CompletableDeferred<Unit>? = null

    /** What runs as a change is saved: where the process dies, in a test. */
    private var onSavePending: (PendingMembershipChangeEntity) -> Unit = {}

    /** What the group sync reads when the changer has it run, and how many times it ran. */
    private var onSync: suspend () -> Unit = {}
    private var syncs = 0

    private val resends = ResendsBooked()

    /** The members of the group as each change rebuilt who may wake Alice, see [push.WakeList]. */
    private val wakeListRebuilds = mutableListOf<Set<GroupMemberEntity>>()

    private val sender = GroupMembershipSender(
        addMembers = { _, body ->
            posted()
            additions += body
            serverGate?.await()

            val answer = addAnswers.removeFirstOrNull() ?: addAnswer

            if (answer == GroupMembershipAnswer.Made) stored(previousKey = body.previousKey, keyCopy = body.keyCopy)

            answer
        },
        removeMembers = { _, body ->
            posted()
            removals += body

            if (removeAnswer == GroupMembershipAnswer.Made) stored(previousKey = body.previousKey, keyCopy = body.keyCopy)

            removeAnswer
        },
        removeDeparted = { _, body ->
            posted()
            departedRemovals += body

            if (departedAnswer == GroupMembershipAnswer.Made) {
                stored(previousKey = body.previousKey, keyCopy = body.keyCopy)
            }

            departedAnswer
        },
        replaceCommit = { _, body ->
            posted()
            replacements += body

            if (replaceAnswer == GroupMembershipAnswer.Made) {
                stored(previousKey = body.previousKey, keyCopy = body.keyCopy)
            }

            replaceAnswer
        },
        rejoin = { _, _ -> error("Alice holds every group here.") },
        group = { groups[it] },
        listPending = { pending.values + outdated.values },
        dropPending = { pending.remove(it) },
        markOutdated = { groupUuid ->
            outdated[groupUuid] = checkNotNull(pending.remove(groupUuid)).copy(isOutdated = true)
        },
        dropOutdated = { outdated.remove(it) },
        forgetRejoin = { error("Alice holds every group here.") },
        replaceMembers = { groupUuid, groupMembers -> members[groupUuid] = groupMembers },
        recordSignatureKeys = { recorded ->
            recorded.forEach { signatureKeys[it.userUuid] = it.signatureKey }
        },
        rebuildWakeList = { wakeListRebuilds += members.getValue(GROUP).toSet() },
        takeCommittedKey = { key ->
            groups[key.groupUuid] = groups.getValue(key.groupUuid).withKey(groupKey = key.groupKey, keyGeneration = key.generation)
            pending.remove(key.groupUuid)
        },
        client = { Result.Success(alice) },
        receiveAccountDeletions = { userUuids -> deletedAccounts += userUuids },
        resendScheduler = resends,
    )

    private val changer = GroupMembershipChanger(
        userUuid = {
            readable(VaultRead.SESSION)
            sessionUuid
        },
        restoreContacts = {
            readable(VaultRead.CONTACTS)
            Result.Success(Unit)
        },
        contacts = { contacts },
        blocked = { blocked },
        client = {
            readable(VaultRead.IDENTITY)
            Result.Success(alice)
        },
        keyCopySealer = {
            readable(VaultRead.KEY_RING)
            keyCopies.sealer()
        },
        consumeKeyPackage = { userUuid ->
            fetched += userUuid
            keyPackages[userUuid] ?: Result.Success(keyPackageOf(clientOf(userUuid)))
        },
        group = { groups[it] },
        hasPendingChange = { it in pending || it in outdated || it in pendingCreations },
        savePending = ::save,
        outdatedChanges = { outdated.values.toList() },
        dropOutdated = { outdated.remove(it) },
        bookResend = sender::bookResend,
        send = sender::send,
        ownProfileKey = {
            readable(VaultRead.PROFILE_KEY)
            aliceProfileKey.takeIf { isVaultAnswering }
        },
        contactProfileKey = { contactProfileKeys[it] },
        learnedProfileKey = { learnedProfileKeys[it] },
        oweProfileKey = { groupUuid ->
            groups[groupUuid] = groups.getValue(groupUuid).copy(handedProfileKeyHash = null)
        },
        syncGroups = {
            syncs++
            onSync()
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

    /**
     * The Welcome lets Dave in at the epoch the addition leads to, whose key he derives as Alice does,
     * and the link the addition carried leads back from it to the key the fields are sealed under.
     */
    @Test
    fun `a newcomer joins from the Welcome and opens the fields through the link back from the key he derives`() =
        runBlocking<Unit> {
            add(DAVE)

            val body = additions.single()
            val welcome = Base64.Default.decode(body.welcomeMessage)
            val inspection = dave.inspectWelcome(welcome = welcome, expectedGroupUuid = GROUP)

            assertEquals(ALICE, inspection.committerUserUuid)
            assertEquals(listOf(ALICE, BOB, CAROL), inspection.memberUuids)

            val daveGroupId = dave.joinGroup(
                welcome = welcome,
                expectedGroupUuid = GROUP,
                expectedMembers = inspection.memberUuids,
                pinMembers = false,
            )
            val daveKey = dave.contentKeyIn(mlsGroupId = daveGroupId, groupUuid = GROUP)

            assertEquals(setOf(ALICE, BOB, CAROL, DAVE), dave.membersOf(daveGroupId))
            assertContentEquals(groups.getValue(GROUP).groupKey, daveKey)

            val firstKey = assertNotNull(body.previousKey.openKeyLink(groupKey = daveKey, groupUuid = GROUP, generation = 2))

            assertContentEquals(groupKey, firstKey)
            assertEquals(
                FIELDS.title,
                serverVersion.fields.unseal(groupKey = firstKey, currencyKey = firstKey, groupUuid = GROUP)?.title,
            )
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
        assertEquals(2L, groups.getValue(GROUP).keyGeneration)
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
    fun `a removal the server takes is applied and its members recorded`() = runBlocking<Unit> {
        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        assertEquals(listOf(CAROL), removals.single().removedMemberUuids)
        assertEquals(rowsOf(ALICE, BOB).toSet(), members.getValue(GROUP).toSet())
        assertEquals(listOf(rowsOf(ALICE, BOB).toSet()), wakeListRebuilds)
        assertEquals(setOf(ALICE, BOB), aliceRoster().memberUuids.toSet())
        assertTrue(pending.isEmpty())
        assertEquals(2L, aliceRoster().epoch)
    }

    /**
     * What the server stores with the Commit for the generation it leads to: the key of the epoch it
     * was built in, sealed under the next one, which the library derived before anything was applied,
     * and Alice's copy of the next one. Once taken and applied, the next one is the group's, backed up.
     */
    @Test
    fun `a Commit carries the link back to the key of its epoch and the creator's copy of the next`() =
        runBlocking<Unit> {
            remove(CAROL)

            val body = removals.single()
            val group = groups.getValue(GROUP)
            val nextKey = alice.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)

            assertEquals(2L, group.keyGeneration)
            assertContentEquals(nextKey, group.groupKey)
            assertFalse(groupKey contentEquals nextKey)
            assertTrue(group.isBackedUp)
            assertContentEquals(groupKey, body.previousKey.openKeyLink(groupKey = nextKey, groupUuid = GROUP, generation = 2))
            assertContentEquals(
                nextKey,
                body.keyCopy.openKeyCopy(sealingKey = server.sealingKey, groupUuid = GROUP, keyGeneration = 2),
            )
            assertContentEquals(nextKey, server.keyOf(GROUP))
        }

    /** Every member in the epoch derives the same key, and nobody chose it. */
    @Test
    fun `the members who remain derive the key the creator took once they read the Commit`() = runBlocking<Unit> {
        remove(CAROL)

        val commit = assertIs<ProcessedMessage.Commit>(bob.process(bobGroupId, removals.single().commitMessage))

        assertEquals(ALICE, commit.committerUserUuid)
        assertEquals(setOf(ALICE, BOB), bob.membersOf(bobGroupId))
        assertContentEquals(groups.getValue(GROUP).groupKey, bob.contentKeyIn(mlsGroupId = bobGroupId, groupUuid = GROUP))
    }

    /** She stays in the epoch the removal ended, and the key of the next is no part of it. */
    @Test
    fun `a removed member finds out from the Commit and cannot derive the next key`() = runBlocking<Unit> {
        remove(CAROL)

        val commit = assertIs<ProcessedMessage.Commit>(carol.process(carolGroupId, removals.single().commitMessage))

        assertTrue(commit.removedSelf)
        assertEquals(FIRST_KEY_GENERATION, carol.epochIn(mlsGroupId = carolGroupId))
        assertContentEquals(groupKey, carol.contentKeyIn(mlsGroupId = carolGroupId, groupUuid = GROUP))
        assertFalse(groups.getValue(GROUP).groupKey contentEquals carol.contentKeyIn(mlsGroupId = carolGroupId, groupUuid = GROUP))
    }

    @Test
    fun `each Commit is one generation past the one before it`() = runBlocking<Unit> {
        remove(CAROL)
        add(DAVE)

        assertEquals(3L, groups.getValue(GROUP).keyGeneration)
        assertEquals(listOf(2L, 3L), serverLinks.map { it.generation })
        assertEquals(3L, server.generationOf(GROUP))
    }

    /**
     * Dave joins at the third epoch, deriving its key alone: the links Alice's Commits stored lead
     * him back to the first, which the group's information is sealed under.
     */
    @Test
    fun `a member added after two Commits gets the first key back through the chain`() = runBlocking<Unit> {
        remove(CAROL)
        add(DAVE)

        val daveGroupId = dave.joinGroup(
            welcome = Base64.Default.decode(additions.single().welcomeMessage),
            expectedGroupUuid = GROUP,
            expectedMembers = listOf(ALICE, BOB),
            pinMembers = false,
        )
        val daveKeys = mutableMapOf(3L to dave.contentKeyIn(mlsGroupId = daveGroupId, groupUuid = GROUP))
        val daveGroup = groups.getValue(GROUP).copy(mlsGroupId = daveGroupId)
        val chain = GroupKeyChain(
            keyLinks = { Result.Success(serverLinks.toList()) },
            group = { daveGroup },
            heldKeys = { groupUuid ->
                daveKeys.map { (generation, key) ->
                    GroupKeyEntity(groupUuid = groupUuid, generation = generation, groupKey = key)
                }
            },
            storeRecovered = { _, keys -> keys.forEach { daveKeys[it.generation] = it.groupKey } },
        )

        val first = chain.keyOf(GROUP, FIRST_KEY_GENERATION) { key ->
            serverVersion.unseal(groupKey = key, currencyKey = key, groupUuid = GROUP) != null
        }

        assertContentEquals(groupKey, assertIs<Result.Success<ByteArray>>(first).data)
        assertEquals(setOf(1L, 2L, 3L), daveKeys.keys)
    }

    @Test
    fun `a change applied records the signature key each member holds in the tree`() = runBlocking<Unit> {
        add(DAVE)

        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), signatureKeys.keys)
        assertContentEquals(dave.signaturePublicKey(), signatureKeys[DAVE])
    }

    /** An earlier send of the same removal was stored, and its answer lost. */
    @Test
    fun `a 409 to a removal counts as made`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        assertTrue(pending.isEmpty())
        assertEquals(2L, groups.getValue(GROUP).keyGeneration)
    }

    @Test
    fun `a removal the server refuses discards its Commit`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)

        assertEquals(Result.Error(GroupMembershipError.Network(NetworkError.BAD_REQUEST)), remove(CAROL))

        assertNothingChanged()
    }

    @Test
    fun `a 429 to a removal keeps it pending with the group on its key`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 42)

        assertEquals(Result.Success(MembershipChangeOutcome.PENDING), remove(CAROL))

        assertEquals(MembershipChangeKind.REMOVAL, pending[GROUP]?.kind)
        assertTrue(aliceRoster().hasPendingCommit)
        assertContentEquals(groupKey, groups.getValue(GROUP).groupKey)
        assertEquals(listOf(60L, 42L), resends.seconds)
    }

    @Test
    fun `a removal never answered is applied once a resend hears 409`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        assertEquals(Result.Success(MembershipChangeOutcome.PENDING), remove(CAROL))

        removeAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(2, removals.size)
        assertEquals(removals[0], removals[1])
        assertTrue(pending.isEmpty())
        assertEquals(setOf(ALICE, BOB), aliceRoster().memberUuids.toSet())
        assertEquals(2L, groups.getValue(GROUP).keyGeneration)
        assertTrue(groups.getValue(GROUP).isBackedUp)
    }

    /** A change asked for twice is made once. */
    @Test
    fun `somebody no longer in the group is not removed again`() = runBlocking<Unit> {
        remove(CAROL)

        assertEquals(Result.Success(MembershipChangeOutcome.MADE), remove(CAROL))

        assertEquals(1, removals.size)
    }

    /**
     * Bob removed Carol, who departed, for the very epoch Alice built her addition in, and the server
     * took his first, refusing hers for it: the sync reads his, and the addition is built again over
     * it, Carol gone by then.
     */
    @Test
    fun `a Commit another member's beat to its epoch is discarded and made again once the groups are synced`() =
        runBlocking<Unit> {
            addAnswers += GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = 2)
            onSync = { aliceReadsBobRemoving(CAROL) }

            assertEquals(Result.Success(MembershipChangeOutcome.MADE), add(DAVE))

            assertEquals(1, syncs)
            assertEquals(2, additions.size)
            assertTrue(pending.isEmpty())
            assertEquals(setOf(ALICE, BOB, DAVE), aliceRoster().memberUuids.toSet())
            assertEquals(3L, aliceRoster().epoch)
            assertEquals(3L, groups.getValue(GROUP).keyGeneration)

            // Built over Bob's Commit: the link leads back to the key of the epoch it took the group to.
            val second = additions.last()
            val secondEpochKey = assertNotNull(
                second.previousKey.openKeyLink(groupKey = groups.getValue(GROUP).groupKey, groupUuid = GROUP, generation = 3),
            )

            assertFalse(groupKey contentEquals secondEpochKey)
        }

    /** Once: the group moved on again meanwhile, or the sync could not read what got there first. */
    @Test
    fun `a Commit beaten to its epoch twice is refused as outdated`() = runBlocking<Unit> {
        addAnswer = GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = 2)

        assertEquals(Result.Error(GroupMembershipError.Outdated), add(DAVE))

        assertEquals(1, syncs)
        assertEquals(2, additions.size)
        assertNothingChanged()
    }

    /** The sync's own removal: it decides again itself, see GroupSync. */
    @Test
    fun `a removal of those who departed beaten to its epoch is refused as outdated with no sync`() =
        runBlocking<Unit> {
            departedAnswer = GroupMembershipAnswer.KeyGenerationNotCurrent(keyGeneration = 2)

            assertEquals(
                Result.Error(GroupMembershipError.Outdated),
                withGroupsLock { changer.removeDepartedUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL)) },
            )

            assertEquals(0, syncs)
            assertNothingChanged()
        }

    /**
     * Through the server's route for those who departed, whoever makes it: what every member's device
     * takes from anybody when it removes exactly those the server stored it as removing.
     */
    @Test
    fun `a removal of those who departed goes through their route with its link and copy`() = runBlocking<Unit> {
        assertEquals(
            Result.Success(MembershipChangeOutcome.MADE),
            withGroupsLock { changer.removeDepartedUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL)) },
        )

        val body = departedRemovals.single()

        assertEquals(listOf(CAROL), body.removedMemberUuids)
        assertTrue(removals.isEmpty())
        assertEquals(rowsOf(ALICE, BOB).toSet(), members.getValue(GROUP).toSet())
        assertEquals(2L, groups.getValue(GROUP).keyGeneration)
        assertTrue(groups.getValue(GROUP).isBackedUp)

        val commit = assertIs<ProcessedMessage.Commit>(
            bob.process(bobGroupId, body.commitMessage, departedUuids = body.removedMemberUuids),
        )

        assertEquals(setOf(ALICE, BOB), bob.membersOf(bobGroupId))
        assertContentEquals(groups.getValue(GROUP).groupKey, bob.contentKeyIn(mlsGroupId = bobGroupId, groupUuid = GROUP))
    }

    /** Not departed any more, or never: the server checks, and writes nothing. */
    @Test
    fun `a removal of those who departed the server refuses discards its Commit`() = runBlocking<Unit> {
        departedAnswer = GroupMembershipAnswer.Refused(error = NetworkError.BAD_REQUEST)

        assertEquals(
            Result.Error(GroupMembershipError.Network(NetworkError.BAD_REQUEST)),
            withGroupsLock { changer.removeDepartedUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL)) },
        )

        assertNothingChanged()
    }

    @Test
    fun `a removal of those who departed kept pending is sent again as it was`() = runBlocking<Unit> {
        departedAnswer = GroupMembershipAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        withGroupsLock { changer.removeDepartedUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL)) }

        assertEquals(MembershipChangeKind.DEPARTED_REMOVAL, pending[GROUP]?.kind)

        departedAnswer = GroupMembershipAnswer.AlreadyMade

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(2, departedRemovals.size)
        assertEquals(departedRemovals[0], departedRemovals[1])
        assertTrue(pending.isEmpty())
        assertEquals(2L, groups.getValue(GROUP).keyGeneration)
    }

    /**
     * Built in the epoch Alice's device is at, the one every member's device stayed in, and named so:
     * every member's device takes it from her, whatever it removes.
     */
    @Test
    fun `the creator's replacement is a Commit for the epoch her device is at removing whoever is named`() =
        runBlocking<Unit> {
            assertEquals(
                Result.Success(MembershipChangeOutcome.MADE),
                withGroupsLock { changer.replaceUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL, MALLORY)) },
            )

            val body = replacements.single()

            assertEquals(FIRST_KEY_GENERATION, body.replacesEpoch)
            assertEquals(listOf(CAROL), body.removedMemberUuids)
            assertEquals(2L, groups.getValue(GROUP).keyGeneration)

            val commit = assertIs<ProcessedMessage.Commit>(bob.process(bobGroupId, body.commitMessage))

            assertEquals(ALICE, commit.committerUserUuid)
            assertEquals(setOf(ALICE, BOB), bob.membersOf(bobGroupId))
        }

    /** A Commit changing none of the members, still taking every device to the next epoch. */
    @Test
    fun `a replacement naming nobody still in the group changes no member`() = runBlocking<Unit> {
        withGroupsLock { changer.replaceUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(MALLORY)) }

        val body = replacements.single()

        assertTrue(body.removedMemberUuids.isEmpty())

        val commit = assertIs<ProcessedMessage.Commit>(bob.process(bobGroupId, body.commitMessage))

        assertEquals(2uL, commit.epoch)
        assertEquals(setOf(ALICE, BOB, CAROL), bob.membersOf(bobGroupId))
    }

    /** A replacement by an owner the server handed the group to would be refused by every device. */
    @Test
    fun `only the creator replaces a Commit`() = runBlocking<Unit> {
        groups[GROUP] = groups.getValue(GROUP).withOwner(BOB)

        assertEquals(
            Result.Error(GroupMembershipError.NotCreator),
            withGroupsLock { changer.replaceUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL)) },
        )

        assertTrue(replacements.isEmpty())
    }

    /** Nothing to replace, the server says: the replacement is refused for good, and discarded. */
    @Test
    fun `a replacement the server finds nothing to replace for is discarded`() = runBlocking<Unit> {
        replaceAnswer = GroupMembershipAnswer.Refused(error = NetworkError.CONFLICT)

        assertEquals(
            Result.Error(GroupMembershipError.Network(NetworkError.CONFLICT)),
            withGroupsLock { changer.replaceUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL)) },
        )

        assertNothingChanged()
    }

    /** The process dies between the server taking the addition and its Commit applied. */
    @Test
    fun `an addition taken and applied whose change outlived it is only recorded by the next sync`() =
        runBlocking<Unit> {
            addAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 1)

            add(DAVE)

            // As the sender would have, short of taking the key and dropping the change.
            alice.applyPendingCommitIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP)

            assertEquals(Result.Success(Unit), sender.resendPending())

            assertEquals(1, additions.size)
            assertEquals(2L, aliceRoster().epoch)
            assertTrue(pending.isEmpty())
            assertEquals(rowsOf(ALICE, BOB, CAROL, DAVE).toSet(), members.getValue(GROUP).toSet())

            // The key of the epoch it applied in, which the group is still in, derived then.
            assertEquals(2L, groups.getValue(GROUP).keyGeneration)
            assertContentEquals(
                alice.contentKeyIn(mlsGroupId = creation.mlsGroupId, groupUuid = GROUP),
                groups.getValue(GROUP).groupKey,
            )
        }

    /** The process dies between a refused Commit discarded and its change dropped. */
    @Test
    fun `a change whose Commit was discarded is dropped by the next sync without being sent`() = runBlocking<Unit> {
        removeAnswer = GroupMembershipAnswer.RateLimited(retryAfterSeconds = 1)

        remove(CAROL)

        alice.discardPendingCommitIn(mlsGroupId = creation.mlsGroupId)

        assertEquals(Result.Success(Unit), sender.resendPending())

        // Neither sent again nor taken for a removal applied.
        assertEquals(1, removals.size)
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

    /** The creator never leaves: nobody else could add anybody to the group. */
    @Test
    fun `the creator removing themselves is a bug`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { remove(ALICE) }
    }

    @Test
    fun `a change asked for on a locked device changes nothing`() = runBlocking<Unit> {
        locked += VaultRead.SESSION

        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), add(DAVE))
        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), remove(CAROL))

        assertTrue(fetched.isEmpty())
        assertTrue(additions.isEmpty())
        assertTrue(removals.isEmpty())
        assertNothingChanged()
    }

    /** A sync holds the lock past the moment the device locks, and the removal waits for it. */
    @Test
    fun `a removal that waited on a sync as the device locked changes nothing`() = runBlocking<Unit> {
        val syncGate = CompletableDeferred<Unit>()
        val sync = launch { withGroupsLock { syncGate.await() } }
        yield()

        val removal = async { remove(CAROL) }
        yield()

        locked += VaultRead.SESSION
        syncGate.complete(Unit)

        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), removal.await())
        sync.join()

        assertTrue(removals.isEmpty())
        assertNothingChanged()
    }

    /**
     * Who is signed in is read once the lock is held, the only read refused here: read before the
     * wait, it would go through, and so would everything after it, Dave being added.
     */
    @Test
    fun `an addition that waited on a sync as the device locked asks for no key package`() = runBlocking<Unit> {
        val syncGate = CompletableDeferred<Unit>()
        val sync = launch { withGroupsLock { syncGate.await() } }
        yield()

        val addition = async { add(DAVE) }
        yield()

        locked += VaultRead.SESSION
        syncGate.complete(Unit)

        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), addition.await())
        sync.join()

        assertTrue(fetched.isEmpty())
        assertTrue(additions.isEmpty())
        assertNothingChanged()
    }

    /** Its identity is read from the vault until this process first opens it. */
    @Test
    fun `a change whose MLS client cannot open on a locked device changes nothing`() = runBlocking<Unit> {
        locked += VaultRead.IDENTITY

        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), add(DAVE))
        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), remove(CAROL))

        assertTrue(fetched.isEmpty())
        assertNothingChanged()
    }

    /** Until a restore has gone through since the user signed in. */
    @Test
    fun `an addition whose contacts a locked device cannot bring back changes nothing`() = runBlocking<Unit> {
        locked += VaultRead.CONTACTS

        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), add(DAVE))

        assertTrue(fetched.isEmpty())
        assertNothingChanged()
    }

    /** Read before any key package is asked for, which would otherwise be spent for nothing. */
    @Test
    fun `an addition whose creator's profile key a locked device cannot read asks for no key package`() =
        runBlocking<Unit> {
            locked += VaultRead.PROFILE_KEY

            assertEquals(Result.Error(GroupMembershipError.DeviceLocked), add(DAVE))

            assertTrue(fetched.isEmpty())
            assertNothingChanged()
        }

    /**
     * Every Commit carries the creator's copy of the key it leads to, sealed under the key ring: read
     * before anything is built, which a locked device leaves for nothing.
     */
    @Test
    fun `a removal whose next key's copy a locked device cannot seal changes nothing`() = runBlocking<Unit> {
        locked += VaultRead.KEY_RING

        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), remove(CAROL))

        assertTrue(removals.isEmpty())
        assertNothingChanged()
    }

    /** Read before any key package is asked for, which would otherwise be spent for nothing. */
    @Test
    fun `an addition whose key ring a locked device cannot read asks for no key package`() = runBlocking<Unit> {
        locked += VaultRead.KEY_RING

        assertEquals(Result.Error(GroupMembershipError.DeviceLocked), add(DAVE))

        assertTrue(fetched.isEmpty())
        assertNothingChanged()
    }

    @Test
    fun `a removal whose next key's copy the key ring cannot seal changes nothing`() = runBlocking<Unit> {
        server.isKeyRingAnswering = false

        assertEquals(
            Result.Error(GroupMembershipError.Vault(VaultError.Network(NetworkError.UNREACHABLE))),
            remove(CAROL),
        )

        assertTrue(removals.isEmpty())
        assertNothingChanged()
    }

    /** The sync stops its pass at the first read a locked device refuses, see GroupSyncError.DeviceLocked. */
    @Test
    fun `a removal of those who departed by the sync on a locked device throws for the sync to stop`() =
        runBlocking<Unit> {
            locked += VaultRead.KEY_RING

            assertFailsWith<IllegalStateException> {
                withGroupsLock { changer.removeDepartedUnderGroupsLock(groupUuid = GROUP, userUuids = setOf(CAROL)) }
            }

            assertTrue(departedRemovals.isEmpty())
            assertNothingChanged()
        }

    private suspend fun add(vararg userUuids: String) = add(userUuids.toSet())

    private suspend fun add(userUuids: Set<String>) = changer.add(groupUuid = GROUP, userUuids = userUuids)

    private suspend fun remove(vararg userUuids: String) =
        changer.remove(groupUuid = GROUP, userUuids = userUuids.toSet())

    private fun readable(read: VaultRead) = check(read !in locked) { DEVICE_LOCKED }

    private fun save(change: PendingMembershipChangeEntity) {
        onSavePending(change)
        pending[change.groupUuid] = change
    }

    private fun posted() {
        pendingWhenPosted += pending[GROUP]?.kind
        bookedWhenPosted += resends.seconds.toList()
    }

    private suspend fun aliceRoster() = alice.rosterOf(mlsGroupId = creation.mlsGroupId)

    /**
     * Has Alice's group sync read Bob's removal of [userUuid], who departed, which the server took for
     * the epoch Alice's device is at: as every member's device, hers takes it, removing exactly whom
     * the server stored it as removing, and the key of the epoch it leads to is the group's.
     */
    private suspend fun aliceReadsBobRemoving(userUuid: String) {
        val commit = bob.removeMembersFrom(mlsGroupId = bobGroupId, userUuids = listOf(userUuid))

        bob.applyPendingCommitIn(mlsGroupId = bobGroupId, groupUuid = GROUP)

        val read = alice.processUnderPolicy(
            mlsGroupId = creation.mlsGroupId,
            message = commit,
            groupUuid = GROUP,
            creatorUuid = ALICE,
            departedUuids = listOf(userUuid),
        )
        val key = checkNotNull(read.keyAfterCommit)

        members[GROUP] = checkNotNull(read.membersAfterCommit).map {
            GroupMemberEntity(userUuid = it.userUuid, groupUuid = GROUP, isAdmin = it.userUuid == ALICE)
        }
        groups[GROUP] = groups.getValue(GROUP).withDerivedKey(groupKey = key.key, keyGeneration = key.epoch)
    }

    /** That [handedOver] is the key to the profile of every member once Dave is in, as Alice holds them. */
    private fun assertEveryMemberHandedOver(handedOver: Map<String, ByteArray>) {
        assertEquals(setOf(ALICE, BOB, CAROL, DAVE), handedOver.keys)
        assertContentEquals(aliceProfileKey, handedOver[ALICE])
        assertContentEquals(contactProfileKeys[BOB], handedOver[BOB])
        assertContentEquals(contactProfileKeys[CAROL], handedOver[CAROL])
        assertContentEquals(contactProfileKeys[DAVE], handedOver[DAVE])
    }

    private fun memberUuids() = members.getValue(GROUP).mapTo(HashSet()) { it.userUuid }

    /**
     * Has the server store the link and the copy of the key the Commit pending in Alice's group leads
     * to, as it stores them with the Commit, at the generation one past the epoch she is in.
     */
    private suspend fun stored(previousKey: EncryptedFieldDto, keyCopy: EncryptedFieldDto) {
        val generation = aliceRoster().epoch + 1

        serverLinks += GroupKeyLinkDto(generation = generation, publisherUuid = ALICE, previousKey = previousKey)
        server.copies[GROUP] = keyCopy
        server.generations[GROUP] = generation
    }

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

    /**
     * What a member's group sync makes of [message], waiting for them in the group [mlsGroupId],
     * stored as removing [departedUuids] for having departed.
     */
    private fun MlsClient.process(
        mlsGroupId: ByteArray,
        message: String,
        departedUuids: List<String> = emptyList(),
    ): ProcessedMessage =
        loadGroup(mlsGroupId = mlsGroupId).use { group ->
            group.processIncomingMessage(
                message = Base64.Default.decode(message),
                policy = CommitPolicy(creatorUuid = ALICE, departedUuids = departedUuids),
            )
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
    keyGeneration: Long = this.keyGeneration,
    isBackedUp: Boolean = this.isBackedUp,
    handedProfileKeyHash: String? = this.handedProfileKeyHash,
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

/**
 * [this] as `GroupDao.takeCommittedKey` leaves it: the Commit carried the creator's copy of
 * [groupKey], the generation [keyGeneration] of its keys.
 */
private fun GroupEntity.withKey(groupKey: ByteArray, keyGeneration: Long) =
    copy(groupKey = groupKey, keyGeneration = keyGeneration, isBackedUp = true)

/** [this] once a sync read a Commit taking it to the epoch of [groupKey], [keyGeneration]. */
private fun GroupEntity.withDerivedKey(groupKey: ByteArray, keyGeneration: Long) =
    copy(groupKey = groupKey, keyGeneration = keyGeneration, isBackedUp = false)

private fun GroupEntity.withOwner(ownerUuid: String) = copy(ownerUuid = ownerUuid)

/** [this] as a log-out and the group sync bringing it back leave it. */
private fun GroupEntity.restored() = copy(mlsGroupId = null)
