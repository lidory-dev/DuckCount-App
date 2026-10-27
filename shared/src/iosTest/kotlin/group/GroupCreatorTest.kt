package group

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import message.ResendsBooked
import mls.ConsumeKeyPackageDto
import mls.InMemoryMlsStore
import mls.holdsGroup
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.isOneTimeExpenseCreditor
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
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"

/**
 * What becomes of a group Alice creates with Bob and Carol, two of her contacts, whatever the
 * server answers: with every MLS client made through the library itself, Alice pinning both as
 * becoming contacts does, and the database and the server stood in for.
 */
class GroupCreatorTest {

    private val aliceStore = InMemoryMlsStore()

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = aliceStore)
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    /** The user UUID the session names, Alice's unless a test says otherwise. */
    private var sessionUuid = ALICE

    /** Who Alice's contacts are, see `ContactDao.getUserUuids`. */
    private var contacts = listOf(BOB, CAROL)

    /** Who the vault blocks, see `IdentityConflictDao.getBlocked`. */
    private var blocked = emptyList<String>()

    /** What the server hands out for a key package of each user, theirs unless a test says otherwise. */
    private val keyPackages = mutableMapOf<String, Result<ConsumeKeyPackageDto, NetworkError>>()

    /** Whose key package was asked for. */
    private val fetched = mutableListOf<String>()

    /** What the server answers the next creation. */
    private var answer: GroupCreationAnswer = GroupCreationAnswer.Created

    /** The creations that reached the server. */
    private val posted = mutableListOf<Post>()

    /** Whether the server holds on to the next creation without ever answering it. */
    private var isServerHanging = false

    /** The resends booked by the time each creation reached the server. */
    private val bookedWhenPosted = mutableListOf<List<Long>>()

    private val groups = mutableMapOf<String, GroupEntity>()
    private val members = mutableMapOf<String, List<GroupMemberEntity>>()
    private val pending = mutableMapOf<String, PendingGroupCreationEntity>()

    /** Whom the server's refusals named as having deleted their account. */
    private val deletedAccounts = mutableListOf<String>()

    private val resends = ResendsBooked()

    /** The keys the vault holds, by group. */
    private val backedUp = mutableMapOf<String, ByteArray>()

    /** Whether the vault takes a key without its answer coming back, as a request timing out once sent would. */
    private var isBackUpAnswerLost = false

    private val sender = GroupCreationSender(
        post = { body ->
            val groupUuid = pending.values.singleOrNull { it.toBody() == body }?.groupUuid

            posted += Post(
                body = body,
                groupUuid = groupUuid,
                wasSaved = groupUuid != null && groups[groupUuid]?.isBackedUp == false,
            )
            bookedWhenPosted += resends.seconds.toList()

            if (isServerHanging) awaitCancellation()

            answer
        },
        dropPending = { groupUuid -> pending.remove(groupUuid) },
        deleteRefused = { groupUuid ->
            pending.remove(groupUuid)
            members.remove(groupUuid)
            groups.remove(groupUuid)
        },
        deleteKey = { groupUuid ->
            backedUp.remove(groupUuid)
            Result.Success(Unit)
        },
        listPending = { pending.values.toList() },
        client = { Result.Success(alice) },
        receiveAccountDeletions = { userUuids -> deletedAccounts += userUuids },
        resendScheduler = resends,
    )

    /** What saving the group comes to, its rows kept unless a test says otherwise. */
    private var saveRows: (GroupEntity, List<GroupMemberEntity>, PendingGroupCreationEntity) -> Unit =
        { group, groupMembers, pendingCreation ->
            groups[group.uuid] = group
            members[group.uuid] = groupMembers
            pending[pendingCreation.groupUuid] = pendingCreation
        }

    private val creator = GroupCreator(
        userUuid = { sessionUuid },
        restoreContacts = { Result.Success(Unit) },
        contacts = { contacts },
        blocked = { blocked },
        client = { Result.Success(alice) },
        consumeKeyPackage = { userUuid ->
            fetched += userUuid
            keyPackages[userUuid] ?: Result.Success(keyPackageOf(clientOf(userUuid)))
        },
        save = { group, groupMembers, pendingCreation -> saveRows(group, groupMembers, pendingCreation) },
        bookResend = sender::bookResend,
        send = sender::send,
        backUp = { groupUuid, groupKey ->
            backedUp[groupUuid] = groupKey
            if (!isBackUpAnswerLost) groups[groupUuid] = groups.getValue(groupUuid).backedUp()
        },
    )

    init {
        alice.pinIdentity(userUuid = BOB, signatureKey = bob.signaturePublicKey())
        alice.pinIdentity(userUuid = CAROL, signatureKey = carol.signaturePublicKey())
    }

    @Test
    fun `a group the server takes is saved before it is sent and keeps nothing pending`() = runBlocking<Unit> {
        val groupUuid = assertIs<Result.Success<String>>(create()).data

        val group = assertNotNull(groups[groupUuid])

        assertEquals("Ski trip", group.title)
        assertEquals("Chalet and lift passes", group.description)
        assertEquals(CurrencyCatalog.defaultCurrency, group.currency)
        assertEquals(ALICE, group.ownerUuid)
        assertEquals(32, group.groupKey.size)
        assertTrue(group.isBackedUp)
        assertTrue(alice.holdsGroup(mlsGroupId = assertNotNull(group.mlsGroupId)))

        assertEquals(
            setOf(
                GroupMemberEntity(userUuid = ALICE, groupUuid = groupUuid, isAdmin = true),
                GroupMemberEntity(userUuid = BOB, groupUuid = groupUuid, isAdmin = false),
                GroupMemberEntity(userUuid = CAROL, groupUuid = groupUuid, isAdmin = false),
            ),
            members.getValue(groupUuid).toSet(),
        )

        val (body, postedUuid, wasSaved) = posted.single()

        assertTrue(wasSaved)
        assertEquals(groupUuid, postedUuid)
        assertEquals(setOf(BOB, CAROL), body.invitedMemberUuids.toSet())

        assertTrue(pending.isEmpty())
        assertContentEquals(group.groupKey, backedUp[groupUuid])
    }

    /** One the process does not live to hear answered would otherwise wait for whatever syncs next. */
    @Test
    fun `a resend is booked before the group is first sent`() = runBlocking<Unit> {
        create()

        assertEquals(listOf(listOf(60L)), bookedWhenPosted)
    }

    @Test
    fun `a creation cut short while it is sent stays pending with its resend booked`() = runBlocking<Unit> {
        isServerHanging = true

        val creation = launch { create() }

        while (posted.isEmpty()) yield()

        creation.cancelAndJoin()

        val groupUuid = assertNotNull(posted.single().groupUuid)

        assertTrue(groupUuid in groups)
        assertTrue(groupUuid in pending)
        assertEquals(listOf(60L), resends.seconds)
    }

    /** What each invitee makes of the request: the group's key in the Welcome, which opens its fields. */
    @Test
    fun `every invitee joins from the one Welcome and opens the fields with the key it hands them`() =
        runBlocking<Unit> {
            val groupUuid = assertIs<Result.Success<String>>(create()).data
            val body = posted.single().body
            val welcome = Base64.Default.decode(body.welcomeMessage)

            val inspection = bob.inspectWelcome(welcome = welcome, expectedGroupUuid = groupUuid)

            assertEquals(ALICE, inspection.committerUserUuid)

            val groupKey = assertNotNull(inspection.welcomePayload)

            assertContentEquals(groups.getValue(groupUuid).groupKey, groupKey)

            bob.joinGroup(
                welcome = welcome,
                expectedGroupUuid = groupUuid,
                expectedMembers = listOf(ALICE, CAROL),
                pinMembers = false,
            )

            val fields = SealedGroupFields(title = body.title, description = body.description, currency = body.currency)
                .unseal(groupKey = groupKey, groupUuid = groupUuid)

            assertEquals("Ski trip", fields?.title)

            carol.joinGroup(
                welcome = Base64.Default.decode(body.welcomeMessage),
                expectedGroupUuid = groupUuid,
                expectedMembers = listOf(ALICE, BOB),
                pinMembers = false,
            )
        }

    /** An earlier send of the same creation was stored, and its answer lost. */
    @Test
    fun `a 409 counts as created`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.AlreadyExists

        val groupUuid = assertIs<Result.Success<String>>(create()).data

        assertTrue(groupUuid in groups)
        assertTrue(pending.isEmpty())
        assertTrue(groupUuid in backedUp)
    }

    @Test
    fun `a 410 deletes the group and its MLS state and names who deleted their account`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.DeletedInvitees(userUuids = listOf(CAROL))

        assertEquals(Result.Error(GroupCreationError.Deleted(userUuids = setOf(CAROL))), create())

        assertTrue(groups.isEmpty())
        assertTrue(members.isEmpty())
        assertTrue(pending.isEmpty())
        assertTrue(holdsNoGroup())
        assertEquals(listOf(CAROL), deletedAccounts)
        assertTrue(backedUp.isEmpty())
    }

    @Test
    fun `a request the server refuses deletes the group and its MLS state`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.Refused(error = NetworkError.BAD_REQUEST)

        assertEquals(Result.Error(GroupCreationError.Network(NetworkError.BAD_REQUEST)), create())

        assertTrue(groups.isEmpty())
        assertTrue(pending.isEmpty())
        assertTrue(holdsNoGroup())
        assertTrue(backedUp.isEmpty())
    }

    @Test
    fun `a 429 keeps everything and books a resend after its Retry-After`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.RateLimited(retryAfterSeconds = 42)

        val groupUuid = assertIs<Result.Success<String>>(create()).data

        assertTrue(groupUuid in groups)
        assertTrue(groupUuid in pending)
        assertTrue(groupUuid in backedUp)
        assertEquals(listOf(60L, 42L), resends.seconds)
    }

    @Test
    fun `an answer that never came back keeps everything and books a resend`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        val groupUuid = assertIs<Result.Success<String>>(create()).data

        assertTrue(groupUuid in groups)
        assertTrue(groupUuid in pending)
        assertTrue(groupUuid in backedUp)
        assertEquals(listOf(60L, 60L), resends.seconds)
    }

    /** What the next sync does with a creation left pending. */
    @Test
    fun `a pending creation sent again goes once the server takes it`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.Failed(error = NetworkError.SERVER_ERROR)

        val groupUuid = assertIs<Result.Success<String>>(create()).data

        answer = GroupCreationAnswer.AlreadyExists

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(listOf(groupUuid, groupUuid), posted.map { it.groupUuid })
        assertEquals(posted[0].body, posted[1].body)
        assertTrue(pending.isEmpty())
        assertTrue(groupUuid in groups)
    }

    /**
     * Derived as the server derives it, from Alice's UUID and the nonce kept with the creation, so
     * that nobody else's request can create a group under it. The library derives a one-time
     * expense's UUID alike, so its check of whose expense one is says whose group this is.
     */
    @Test
    fun `the group UUID is derived from the user's and the nonce a resend posts again`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        val groupUuid = assertIs<Result.Success<String>>(create()).data
        val nonce = pending.getValue(groupUuid).toBody().nonce
        val nonceBytes = Base64.Default.decode(nonce)

        assertEquals(16, nonceBytes.size)
        assertTrue(isOneTimeExpenseCreditor(expenseUuid = groupUuid, creditorUuid = ALICE, nonce = nonceBytes))
        assertFalse(isOneTimeExpenseCreditor(expenseUuid = groupUuid, creditorUuid = BOB, nonce = nonceBytes))

        assertEquals(Result.Error(NetworkError.REQUEST_TIMEOUT), sender.resendPending())

        assertEquals(listOf(nonce, nonce), posted.map { it.body.nonce })
    }

    /** The next would fare no better, and the resend booked for this one goes for them all. */
    @Test
    fun `a resend stops at the first creation the server does not answer`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        create()
        create()

        posted.clear()
        resends.seconds.clear()
        answer = GroupCreationAnswer.Failed(error = NetworkError.SERVER_ERROR)

        assertEquals(Result.Error(NetworkError.SERVER_ERROR), sender.resendPending())

        assertEquals(1, posted.size)
        assertEquals(listOf(60L), resends.seconds)
        assertEquals(2, pending.size)
    }

    @Test
    fun `a resend stops at the first creation over the limit`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.Failed(error = NetworkError.REQUEST_TIMEOUT)

        create()
        create()

        posted.clear()
        resends.seconds.clear()
        answer = GroupCreationAnswer.RateLimited(retryAfterSeconds = 120)

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertEquals(1, posted.size)
        assertEquals(listOf(120L), resends.seconds)
        assertEquals(2, pending.size)
    }

    /** A key left there would open nothing ever: no group is listed under its UUID. */
    @Test
    fun `a creation refused at a resend takes its key out of the vault`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.RateLimited(retryAfterSeconds = 42)

        val groupUuid = assertIs<Result.Success<String>>(create()).data

        assertTrue(groupUuid in backedUp)

        answer = GroupCreationAnswer.DeletedInvitees(userUuids = listOf(CAROL))

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertTrue(groups.isEmpty())
        assertTrue(pending.isEmpty())
        assertTrue(backedUp.isEmpty())
        assertTrue(holdsNoGroup())
    }

    /** The vault took the key and its answer was lost, so the group was never recorded as backed up. */
    @Test
    fun `a creation refused at a resend takes out a key the vault took unheard`() = runBlocking<Unit> {
        answer = GroupCreationAnswer.RateLimited(retryAfterSeconds = 42)
        isBackUpAnswerLost = true

        val groupUuid = assertIs<Result.Success<String>>(create()).data

        assertTrue(groupUuid in backedUp)
        assertFalse(groups.getValue(groupUuid).isBackedUp)

        answer = GroupCreationAnswer.DeletedInvitees(userUuids = listOf(CAROL))

        assertEquals(Result.Success(Unit), sender.resendPending())

        assertTrue(groups.isEmpty())
        assertTrue(backedUp.isEmpty())
    }

    @Test
    fun `invitees with no key package are all named and nothing is saved`() = runBlocking<Unit> {
        val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

        contacts = listOf(BOB, CAROL, DAVE)
        alice.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())
        keyPackages[CAROL] = Result.Error(NetworkError.NOT_FOUND)
        keyPackages[DAVE] = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(
            Result.Error(GroupCreationError.Unreachable(userUuids = setOf(CAROL, DAVE))),
            create(inviteeUuids = setOf(BOB, CAROL, DAVE)),
        )

        assertNothingCreated()
    }

    /**
     * Carol's served as something that is no key package at all, Dave's as one of Bob's, which the
     * library refuses for naming somebody else: neither can be invited with, as if they had none.
     */
    @Test
    fun `invitees whose key package cannot be used are all named and nothing is saved`() = runBlocking<Unit> {
        val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

        contacts = listOf(BOB, CAROL, DAVE)
        alice.pinIdentity(userUuid = DAVE, signatureKey = dave.signaturePublicKey())
        keyPackages[CAROL] = Result.Success(ConsumeKeyPackageDto(keyPackage = Base64.Default.encode(ByteArray(64) { 5 })))
        keyPackages[DAVE] = Result.Success(keyPackageOf(bob))

        assertEquals(
            Result.Error(GroupCreationError.Unreachable(userUuids = setOf(CAROL, DAVE))),
            create(inviteeUuids = setOf(BOB, CAROL, DAVE)),
        )

        assertNothingCreated()
    }

    @Test
    fun `a key package that could not be asked for stops everything`() = runBlocking<Unit> {
        keyPackages[CAROL] = Result.Error(NetworkError.NO_INTERNET)

        assertEquals(Result.Error(GroupCreationError.Network(NetworkError.NO_INTERNET)), create())

        assertNothingCreated()
    }

    /** As a server handing over a key package of another identity for Bob would leave Alice. */
    @Test
    fun `an identity that changed names the member and saves nothing`() = runBlocking<Unit> {
        val impostor = MlsClient(userUuid = BOB, secret = ByteArray(32) { 5 }, store = InMemoryMlsStore())

        keyPackages[BOB] = Result.Success(keyPackageOf(impostor))

        assertEquals(Result.Error(GroupCreationError.IdentityChanged(userUuids = setOf(BOB))), create())

        assertNothingCreated()
    }

    /** No UUID derives from it, so there is none to seal the fields to. */
    @Test
    fun `a session naming no UUID is refused before any key package is asked for`() = runBlocking<Unit> {
        sessionUuid = "not-a-uuid"

        assertEquals(Result.Error(GroupCreationError.Crypto), create())

        assertTrue(fetched.isEmpty())
        assertNothingCreated()
    }

    /** The form never selects past the limit, so a caller asking for more is a bug. */
    @Test
    fun `more than 99 invitees are refused before any key package is asked for`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { create(inviteeUuids = strangers(count = 100)) }

        assertTrue(fetched.isEmpty())
    }

    /** Refused for who they are, so the count let them through. */
    @Test
    fun `99 invitees are not too many`() = runBlocking<Unit> {
        val strangers = strangers(count = 99)

        assertEquals(
            Result.Error(GroupCreationError.NotContacts(userUuids = strangers)),
            create(inviteeUuids = strangers),
        )

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `somebody who is not a contact is named before any key package is asked for`() = runBlocking<Unit> {
        contacts = listOf(BOB)

        assertEquals(Result.Error(GroupCreationError.NotContacts(userUuids = setOf(CAROL))), create())

        assertTrue(fetched.isEmpty())
        assertNothingCreated()
    }

    /** Reached over MLS by nobody yet, so whatever key package the server served would be them. */
    @Test
    fun `a contact pinned to no identity is not invited`() = runBlocking<Unit> {
        contacts = listOf(BOB, CAROL, DAVE)

        assertEquals(
            Result.Error(GroupCreationError.NotContacts(userUuids = setOf(DAVE))),
            create(inviteeUuids = setOf(BOB, CAROL, DAVE)),
        )

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a blocked contact is named as an identity that changed before any key package is asked for`() =
        runBlocking<Unit> {
            blocked = listOf(CAROL)

            assertEquals(Result.Error(GroupCreationError.IdentityChanged(userUuids = setOf(CAROL))), create())

            assertTrue(fetched.isEmpty())
        }

    @Test
    fun `a group that could not be saved is deleted again and never sent`() = runBlocking<Unit> {
        saveRows = { _, _, _ -> throw IllegalStateException("disk full") }

        assertEquals(Result.Error(GroupCreationError.Storage), create())

        assertTrue(holdsNoGroup())
        assertTrue(posted.isEmpty())
    }

    private suspend fun create(inviteeUuids: Set<String> = setOf(BOB, CAROL)) =
        creator.create(
            title = "Ski trip",
            description = "Chalet and lift passes",
            currency = CurrencyCatalog.defaultCurrency,
            inviteeUuids = inviteeUuids,
        )

    private fun clientOf(userUuid: String) = when (userUuid) {
        BOB -> bob
        CAROL -> carol
        else -> error("No client for $userUuid")
    }

    private fun keyPackageOf(client: MlsClient): ConsumeKeyPackageDto {
        val keyPackage = client.generateKeyPackages(oneTimeCount = 1u).oneTimeKeyPackages.single()

        return ConsumeKeyPackageDto(keyPackage = Base64.Default.encode(keyPackage))
    }

    /** Whether Alice's store holds no group at all. */
    private fun holdsNoGroup() = aliceStore.keys(namespace_ = MlsNamespace.GROUP_STATE).isEmpty()

    /** [this] as `GroupDao.markBackedUp` leaves it. */
    private fun GroupEntity.backedUp() = GroupEntity(
        uuid = uuid,
        title = title,
        description = description,
        currency = currency,
        ownerUuid = ownerUuid,
        mlsGroupId = mlsGroupId,
        groupKey = groupKey,
        isBackedUp = true,
        isLeft = isLeft,
    )

    private fun assertNothingCreated() {
        assertTrue(groups.isEmpty())
        assertTrue(members.isEmpty())
        assertTrue(pending.isEmpty())
        assertTrue(posted.isEmpty())
        assertTrue(holdsNoGroup())
    }

    private fun strangers(count: Int) =
        (1..count).mapTo(HashSet()) { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }

    /**
     * A creation that reached the server: its [body], the group [groupUuid] it was pending for,
     * and whether that group was saved by then as built, its key not backed up yet.
     */
    private data class Post(val body: CreateGroupBody, val groupUuid: String?, val wasSaved: Boolean)
}
