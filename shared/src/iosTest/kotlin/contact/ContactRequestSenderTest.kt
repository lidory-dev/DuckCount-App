package contact

import database.data.local.entity.GroupEntity
import database.data.local.entity.OutgoingContactRequestEntity
import database.data.remote.SendContactRequestBody
import database.domain.CurrencyCatalog
import group.FIRST_GROUP_REVISION
import group.FIRST_KEY_GENERATION
import kotlinx.coroutines.runBlocking
import mls.ApplicationMessageReading
import mls.ConsumeKeyPackageDto
import mls.InMemoryMlsStore
import mls.MlsIdentityError
import mls.holdsGroup
import mls.inviteeKeyPackage
import mls.readApplicationMessage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.EmptyResult
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** The group of a request Bob sent Carol before, which his store does not hold. */
private val EARLIER_GROUP_ID = ByteArray(32) { 9 }

/**
 * What becomes of a contact request Bob sends Carol, a member of the group Alice created with
 * the two of them, and no contact of his: with every MLS client made through the library itself,
 * Bob having joined the group from Alice's Welcome pinning nobody, as the group sync joins one, and
 * the database and the server stood in for.
 */
class ContactRequestSenderTest {

    private val bobStore = InMemoryMlsStore()

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = bobStore)
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    /** Somebody else's client claiming to be Carol, as a server substituting one would serve for her. */
    private val impostor = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

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

    /** The groups this device holds, see `GroupDao.get`. */
    private val groups = mutableMapOf(GROUP to groupOf(GROUP, mlsGroupId = bobGroupId))

    /** Who Bob's contacts are, see `ContactRepository.isContact`. */
    private val contacts = mutableSetOf<String>()

    /** Who the vault blocks, see `IdentityConflictDao.isBlocked`. */
    private val blocked = mutableSetOf<String>()

    /** Who Bob blocked, see `BlockedUserDao.isBlocked`. */
    private val blockedByUser = mutableSetOf<String>()

    /** What restoring the contacts comes to. */
    private var restored: EmptyResult<ContactSyncError> = Result.Success(Unit)

    /** What opening Bob's client comes to. */
    private var opened: Result<MlsClient, MlsIdentityError> = Result.Success(bob)

    /** What the server hands out for a key package of each user, theirs unless a test says otherwise. */
    private val keyPackages = mutableMapOf<String, Result<ConsumeKeyPackageDto, NetworkError>>()

    /** Whose key package was asked for. */
    private val fetched = mutableListOf<String>()

    /** The signature key Bob had pinned for each of them when their key package was asked for. */
    private val pinnedWhenFetched = mutableListOf<ByteArray?>()

    /** The requests Bob has out, see `OutgoingContactRequestEntity`. */
    private val outgoing = mutableListOf<OutgoingContactRequestEntity>()

    /** The requests recorded, whether they stayed or not. */
    private val recorded = mutableListOf<OutgoingContactRequestEntity>()

    /** How many requests Bob had out at each rebuild of the wake list. */
    private val wakeListRebuilds = mutableListOf<Int>()

    /** The requests that reached the server. */
    private val sent = mutableListOf<Sending>()

    /** What the server answers the next request. */
    private var answer: EmptyResult<NetworkError> = Result.Success(Unit)

    private val profileEncryptionKey = generateSymmetricEncryptionKey()

    private val sender = ContactRequestSender(
        selfUuid = { BOB },
        restoreContacts = { restored },
        isContact = { it in contacts },
        hasStoredOutgoingTo = { recipientUserUuid ->
            outgoing.any { it.recipientUserUuid == recipientUserUuid && it.isStored }
        },
        isBlocked = { it in blocked },
        isBlockedByUser = { it in blockedByUser },
        group = { groups[it] },
        client = { opened },
        consumeKeyPackage = { userUuid ->
            fetched += userUuid
            pinnedWhenFetched += bob.pinnedSignatureKey(userUuid = userUuid)
            keyPackages[userUuid] ?: Result.Success(keyPackageOf(clientOf(userUuid)))
        },
        profileKey = { Result.Success(profileEncryptionKey) },
        insertOutgoing = { request ->
            outgoing += request
            recorded += request
        },
        markStored = { recipientUserUuid, mlsGroupId ->
            markStoredWhere { it.recipientUserUuid == recipientUserUuid && it.mlsGroupId.contentEquals(mlsGroupId) }
        },
        markStoredTo = { recipientUserUuid -> markStoredWhere { it.recipientUserUuid == recipientUserUuid } },
        deleteOutgoing = { recipientUserUuid, mlsGroupId ->
            outgoing.removeAll { it.recipientUserUuid == recipientUserUuid && it.mlsGroupId.contentEquals(mlsGroupId) }
        },
        rebuildWakeList = { wakeListRebuilds += outgoing.size },
        send = { body ->
            sent += Sending(
                body = body,
                outgoing = outgoing.size,
                stored = outgoing.count { it.isStored },
                wakeListRebuilds = wakeListRebuilds.size,
            )
            answer
        },
    )

    @Test
    fun `a request to a member goes to their UUID once recorded and pins them from the group`() = runBlocking<Unit> {
        assertEquals(Result.Success(Unit), sendToCarol())

        // Pinned from the group before her key package was asked for, rather than from it.
        assertEquals(listOf(CAROL), fetched)
        assertContentEquals(carol.signaturePublicKey(), pinnedWhenFetched.single())
        assertContentEquals(carol.signaturePublicKey(), bob.pinnedSignatureKey(userUuid = CAROL))

        val request = outgoing.single()

        assertEquals(CAROL, request.recipientUserUuid)
        assertTrue(bob.holdsGroup(mlsGroupId = request.mlsGroupId))
        assertTrue(request.isStored)
        assertEquals(listOf(1), wakeListRebuilds)

        // Recorded, and the wake list rebuilt with it, before it went out, and stored only after.
        val sending = sent.single()

        assertEquals(CAROL, sending.body.recipientUserUuid)
        assertEquals(1, sending.outgoing)
        assertEquals(1, sending.wakeListRebuilds)
        assertEquals(0, sending.stored)

        // And Carol opens it as any other request, reading the key to Bob's profile.
        val envelope = assertNotNull(ContactRequestEnvelope.parse(ciphertext = sending.body.ciphertext))

        val requestGroupId = carol.joinGroup(
            welcome = envelope.welcome,
            expectedGroupUuid = null,
            expectedMembers = listOf(BOB),
            pinMembers = false,
        )

        val reading = assertIs<ApplicationMessageReading.Read>(
            carol.readApplicationMessage(mlsGroupId = requestGroupId, message = envelope.message),
        )

        assertContentEquals(profileEncryptionKey, assertNotNull(ContactCard.parse(reading.payload)).profileEncryptionKey)
    }

    @Test
    fun `only the member asked is pinned from the group`() = runBlocking<Unit> {
        sendToCarol()

        assertNull(bob.pinnedSignatureKey(userUuid = ALICE))
    }

    @Test
    fun `contacts that cannot be brought back are refused before any key package is spent`() = runBlocking<Unit> {
        restored = Result.Error(ContactSyncError.Storage)

        assertEquals(Result.Error(SendContactRequestError.Contacts(ContactSyncError.Storage)), sendToCarol())

        assertNothingSpent()
    }

    @Test
    fun `the user is refused as themself before any key package is spent`() = runBlocking<Unit> {
        assertEquals(Result.Error(SendContactRequestError.Self), sender.sendToMember(userUuid = BOB, groupUuid = GROUP))

        assertNothingSpent()
    }

    @Test
    fun `a member who already is a contact is refused before any key package is spent`() = runBlocking<Unit> {
        contacts += CAROL

        assertEquals(Result.Error(SendContactRequestError.AlreadyContact), sendToCarol())

        assertNothingSpent()
    }

    /** The server would refuse it too, once her key package was spent on it. */
    @Test
    fun `a member with a request out to them already is refused before any key package is spent`() =
        runBlocking<Unit> {
            outgoing += earlierRequest(isStored = true)

            assertEquals(Result.Error(SendContactRequestError.AlreadyPending), sendToCarol())

            assertTrue(fetched.isEmpty())
            assertTrue(sent.isEmpty())
            assertEquals(1, outgoing.size)
            assertNull(bob.pinnedSignatureKey(userUuid = CAROL))
        }

    @Test
    fun `a blocked member is refused as an identity that changed before any key package is spent`() =
        runBlocking<Unit> {
            blocked += CAROL

            assertEquals(Result.Error(SendContactRequestError.IdentityChanged), sendToCarol())

            assertNothingSpent()
        }

    /** Her answer would be acknowledged unread: unblocking her comes first. */
    @Test
    fun `a member the user blocked is refused before any key package is spent`() = runBlocking<Unit> {
        blockedByUser += CAROL

        assertEquals(Result.Error(SendContactRequestError.Blocked), sendToCarol())

        assertNothingSpent()
    }

    /**
     * The lookup of a username finds out whose it is, spending one of their key packages, and
     * nothing more is spent on it: no group is built, nothing recorded or sent.
     */
    @Test
    fun `a request to somebody the user blocked is refused before anything is built`() = runBlocking<Unit> {
        blockedByUser += CAROL

        assertEquals(
            Result.Error(SendContactRequestError.Blocked),
            sender.sendTo(recipientUserUuid = CAROL, keyPackage = keyPackageOf(carol).keyPackage),
        )

        assertTrue(recorded.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(1, groupsHeld())
        assertNull(bob.pinnedSignatureKey(userUuid = CAROL))
    }

    /** It may never have reached the server, which settles it once she is asked again. */
    @Test
    fun `a request sent in doubt before does not stop the member being asked again`() = runBlocking<Unit> {
        outgoing += earlierRequest(isStored = false)

        assertEquals(Result.Success(Unit), sendToCarol())

        assertEquals(listOf(CAROL), fetched)
        assertEquals(CAROL, sent.single().body.recipientUserUuid)
        assertEquals(listOf(false, true), outgoing.map { it.isStored })
    }

    /** The request the server holds for her is the one sent in doubt, out to her from then on. */
    @Test
    fun `a request refused as pending marks the one sent in doubt before as stored`() = runBlocking<Unit> {
        outgoing += earlierRequest(isStored = false)
        answer = Result.Error(NetworkError.CONFLICT)

        assertEquals(Result.Error(SendContactRequestError.AlreadyPending), sendToCarol())

        val earlier = outgoing.single()

        assertContentEquals(EARLIER_GROUP_ID, earlier.mlsGroupId)
        assertTrue(earlier.isStored)
        assertEquals(1, groupsHeld())
    }

    @Test
    fun `a group this device does not hold is refused before any key package is spent`() = runBlocking<Unit> {
        assertEquals(
            Result.Error(SendContactRequestError.NotInGroup),
            sender.sendToMember(userUuid = CAROL, groupUuid = OTHER_GROUP),
        )

        assertNothingSpent()
    }

    /** Its MLS state went with the removal, and nothing is left to vouch for her. */
    @Test
    fun `a group the user was removed from is refused before any key package is spent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(GROUP, mlsGroupId = bobGroupId, isLeft = true)

        assertEquals(Result.Error(SendContactRequestError.NotInGroup), sendToCarol())

        assertNothingSpent()
    }

    @Test
    fun `a group brought back after a log-out is refused before any key package is spent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(GROUP, mlsGroupId = null)

        assertEquals(Result.Error(SendContactRequestError.NotInGroup), sendToCarol())

        assertNothingSpent()
    }

    /** As when the user is removed from it between the read of the group and the pin. */
    @Test
    fun `a group whose MLS state is gone is refused before any key package is spent`() = runBlocking<Unit> {
        bob.deleteGroup(mlsGroupId = bobGroupId)

        assertEquals(Result.Error(SendContactRequestError.NotInGroup), sendToCarol())

        assertNothingSpent()
    }

    /** Her identity in the group is Alice's word, and the vault disputes the one pinned for Alice. */
    @Test
    fun `a group whose creator is blocked is refused before any key package is spent`() = runBlocking<Unit> {
        blocked += ALICE

        assertEquals(Result.Error(SendContactRequestError.NotInGroup), sendToCarol())

        assertNothingSpent()
    }

    @Test
    fun `a client that cannot be opened is refused before any key package is spent`() = runBlocking<Unit> {
        opened = Result.Error(MlsIdentityError.MissingSession)

        assertEquals(Result.Error(SendContactRequestError.Identity(MlsIdentityError.MissingSession)), sendToCarol())

        assertNothingSpent()
    }

    @Test
    fun `somebody the group does not hold is refused before any key package is spent`() = runBlocking<Unit> {
        assertEquals(
            Result.Error(SendContactRequestError.NotInGroup),
            sender.sendToMember(userUuid = DAVE, groupUuid = GROUP),
        )

        assertTrue(fetched.isEmpty())
        assertTrue(sent.isEmpty())
        assertNull(bob.pinnedSignatureKey(userUuid = DAVE))
    }

    @Test
    fun `a member pinned to another identity already is refused before any key package is spent`() =
        runBlocking<Unit> {
            bob.pinIdentity(userUuid = CAROL, signatureKey = impostor.signaturePublicKey())

            assertEquals(Result.Error(SendContactRequestError.IdentityChanged), sendToCarol())

            assertTrue(fetched.isEmpty())
            assertTrue(sent.isEmpty())
            assertContentEquals(impostor.signaturePublicKey(), bob.pinnedSignatureKey(userUuid = CAROL))
        }

    @Test
    fun `the user's storage failing while pinning is refused before any key package is spent`() =
        runBlocking<Unit> {
            bobStore.failing = setOf(MlsNamespace.IDENTITY_PIN)

            assertEquals(Result.Error(SendContactRequestError.Crypto), sendToCarol())

            bobStore.failing = emptySet()

            assertNothingSpent()
        }

    /** Spent on a request that never goes out, and her key from the group is what stays pinned. */
    @Test
    fun `a key package of somebody else's for the member is refused and builds nothing`() = runBlocking<Unit> {
        keyPackages[CAROL] = Result.Success(keyPackageOf(impostor))

        assertEquals(Result.Error(SendContactRequestError.IdentityChanged), sendToCarol())

        assertEquals(listOf(CAROL), fetched)
        assertTrue(recorded.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(1, groupsHeld())
        assertContentEquals(carol.signaturePublicKey(), bob.pinnedSignatureKey(userUuid = CAROL))
    }

    /** Pinned all the same: it is what any later key package of hers is checked against. */
    @Test
    fun `a member with no key package is not found and stays pinned from the group`() = runBlocking<Unit> {
        keyPackages[CAROL] = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(Result.Error(SendContactRequestError.NotFound), sendToCarol())

        assertTrue(recorded.isEmpty())
        assertTrue(sent.isEmpty())
        assertContentEquals(carol.signaturePublicKey(), bob.pinnedSignatureKey(userUuid = CAROL))
    }

    @Test
    fun `a key package that could not be asked for is a network failure and builds nothing`() =
        runBlocking<Unit> {
            keyPackages[CAROL] = Result.Error(NetworkError.UNREACHABLE)

            assertEquals(Result.Error(SendContactRequestError.Network(NetworkError.UNREACHABLE)), sendToCarol())

            assertTrue(recorded.isEmpty())
            assertEquals(1, groupsHeld())
        }

    /** As the server answers a request already pending from Bob, which a log-out left unrecorded. */
    @Test
    fun `a request the server did not store is forgotten with its group`() = runBlocking<Unit> {
        answer = Result.Error(NetworkError.CONFLICT)

        assertEquals(Result.Error(SendContactRequestError.AlreadyPending), sendToCarol())

        val request = recorded.single()

        assertTrue(outgoing.isEmpty())
        assertEquals(listOf(1, 0), wakeListRebuilds)
        assertFalse(bob.holdsGroup(mlsGroupId = request.mlsGroupId))
        assertEquals(1, groupsHeld())
    }

    /** It may be waiting for Carol all the same, and her answer can only be read in its group. */
    @Test
    fun `a request whose answer was lost stays recorded with its group`() = runBlocking<Unit> {
        answer = Result.Error(NetworkError.REQUEST_TIMEOUT)

        assertEquals(Result.Error(SendContactRequestError.Network(NetworkError.REQUEST_TIMEOUT)), sendToCarol())

        val request = outgoing.single()

        assertTrue(bob.holdsGroup(mlsGroupId = request.mlsGroupId))
        assertFalse(request.isStored)
        assertEquals(listOf(1), wakeListRebuilds)
    }

    /** What the lookup of a username hands over goes through the same, see `ContactRequestRepository`. */
    @Test
    fun `a key package that is not Base64 is refused before anything is built`() = runBlocking<Unit> {
        assertEquals(
            Result.Error(SendContactRequestError.Crypto),
            sender.sendTo(recipientUserUuid = CAROL, keyPackage = "not base64!"),
        )

        assertTrue(recorded.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(1, groupsHeld())
    }

    private suspend fun sendToCarol() = sender.sendToMember(userUuid = CAROL, groupUuid = GROUP)

    /** A request Bob sent Carol before, in a group of its own. */
    private fun earlierRequest(isStored: Boolean) = OutgoingContactRequestEntity(
        mlsGroupId = EARLIER_GROUP_ID,
        recipientUserUuid = CAROL,
        handedProfileKeyHash = "hash",
        isStored = isStored,
    )

    /** Marks stored the requests Bob has out that [matches] picks, as the table's update does. */
    private fun markStoredWhere(matches: (OutgoingContactRequestEntity) -> Boolean) {
        outgoing.indices.filter { matches(outgoing[it]) }.forEach { index ->
            val request = outgoing[index]

            outgoing[index] = OutgoingContactRequestEntity(
                mlsGroupId = request.mlsGroupId,
                recipientUserUuid = request.recipientUserUuid,
                handedProfileKeyHash = request.handedProfileKeyHash,
                isStored = true,
            )
        }
    }

    private fun clientOf(userUuid: String) = when (userUuid) {
        CAROL -> carol
        else -> error("No client for $userUuid")
    }

    private fun keyPackageOf(client: MlsClient): ConsumeKeyPackageDto {
        val keyPackage = client.generateKeyPackages(oneTimeCount = 1u).oneTimeKeyPackages.single()

        return ConsumeKeyPackageDto(keyPackage = Base64.Default.encode(keyPackage))
    }

    /** How many groups Bob's store holds: the one Alice created, plus any request's. */
    private fun groupsHeld() = bobStore.keys(namespace_ = MlsNamespace.GROUP_STATE).size

    /** No key package spent, nothing recorded, sent or built, and Carol pinned to nothing. */
    private fun assertNothingSpent() {
        assertTrue(fetched.isEmpty())
        assertTrue(recorded.isEmpty())
        assertTrue(sent.isEmpty())
        assertNull(bob.pinnedSignatureKey(userUuid = CAROL))
    }

    private fun groupOf(groupUuid: String, mlsGroupId: ByteArray?, isLeft: Boolean = false) = GroupEntity(
        uuid = groupUuid,
        title = "Ski trip",
        description = "Chalet and lift passes",
        currency = CurrencyCatalog.defaultCurrency,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = ALICE,
        mlsGroupId = mlsGroupId,
        groupKey = groupKey,
        keyGeneration = FIRST_KEY_GENERATION,
        isBackedUp = true,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )

    /**
     * A request as it reached the server, with how many Bob had out by then, how many of those
     * were stored, and how many times the wake list had been rebuilt.
     */
    private class Sending(
        val body: SendContactRequestBody,
        val outgoing: Int,
        val stored: Int,
        val wakeListRebuilds: Int,
    )
}
