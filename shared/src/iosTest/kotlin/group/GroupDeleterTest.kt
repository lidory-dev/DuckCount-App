package group

import database.data.local.entity.GroupEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import mls.InMemoryMlsStore
import mls.holdsGroup
import mls.inviteeKeyPackage
import mls.MlsIdentityError
import uniffi.lib_duckcount.MlsClient
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/** What KSafe throws as anything stored with a key a locked device cannot use is read. */
private const val DEVICE_LOCKED = "KSafe: Cannot access Keystore key - device is locked"

/** What the group is created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "",
    currency = CurrencyCatalog.defaultCurrency,
)

/** What the owner's deletion of a group comes to, see [GroupDeleter]. Bob is the one asking. */
class GroupDeleterTest {

    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    private var selfUuid: () -> String? = { BOB }
    private val groups = mutableMapOf<String, GroupEntity>()
    private val pendingChanges = mutableSetOf<String>()

    /** What the server answers a deletion and a listing, and what asked for either, in order. */
    private var answer: Result<Unit, NetworkError> = Result.Success(Unit)
    private var listing: Result<List<GroupDto>, NetworkError> = Result.Success(emptyList())
    private val steps = mutableListOf<String>()

    private var isForgetFailing = false
    private var isClientLocked = false

    /** Why the client opened from what this device stores does not open, if it does not. */
    private var clientFailure: MlsIdentityError? = null

    /** How many times the client that reads the vault, and so needs the server, was opened. */
    private var vaultClientOpens = 0

    /** Whether the MLS state the group's row named was still held as the row went. */
    private var wasStateHeldAsForgotten: Boolean? = null

    private val deleter = GroupDeleter(
        userUuid = { selfUuid() },
        group = { groups[it] },
        hasPendingChange = { it in pendingChanges },
        sendDelete = { groupUuid ->
            steps += "delete $groupUuid"
            answer
        },
        listGroups = {
            steps += "list"
            listing
        },
        forget = { groupUuid ->
            if (isForgetFailing) error("Storage failed")

            steps += "forget $groupUuid"
            groups.remove(groupUuid)
        },
        forgetLeftGroup = { groupUuid ->
            if (isForgetFailing) error("Storage failed")

            steps += "forget left $groupUuid"
            groups[groupUuid]?.mlsGroupId?.let { wasStateHeldAsForgotten = bob.holdsGroup(mlsGroupId = it) }
            groups.remove(groupUuid)
        },
        rebuildWakeList = {
            // Once the group is gone: its members wake Bob no longer.
            steps += "wake list without the group: ${GROUP !in groups}"
        },
        client = {
            vaultClientOpens++

            if (isClientLocked) throw IllegalStateException(DEVICE_LOCKED)

            Result.Success(bob)
        },
        withLocalClient = { block ->
            if (isClientLocked) throw IllegalStateException(DEVICE_LOCKED)

            clientFailure?.let { Result.Error(it) } ?: Result.Success(block(bob))
        },
    )

    @Test
    fun `the creator deleting has the server take it first and forgets the group once it did`() = runBlocking<Unit> {
        val mlsGroupId = bobCreates()

        assertEquals(Result.Success(Unit), deleter.delete(GROUP))

        assertEquals(listOf("delete $GROUP", "forget $GROUP", "wake list without the group: true"), steps)
        assertFalse(bob.holdsGroup(mlsGroupId = mlsGroupId))
    }

    /** A log-out takes every MLS group with it, which a deletion has no use for. */
    @Test
    fun `a group brought back after a log-out may be deleted`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB, mlsGroupId = null)

        assertEquals(Result.Success(Unit), deleter.delete(GROUP))

        assertFalse(GROUP in groups)
    }

    @Test
    fun `a member who is not the owner is refused with nothing sent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE)

        assertEquals(Result.Error(GroupDeleteError.NotOwner), deleter.delete(GROUP))

        assertRefusedWithNothingWritten()
    }

    /** A sync read it as this waited for the lock, or found it gone: out of it, it is not theirs to delete. */
    @Test
    fun `a group the user is no longer in is refused with nothing sent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB, isLeft = true)

        assertEquals(Result.Error(GroupDeleteError.NotOwner), deleter.delete(GROUP))

        assertRefusedWithNothingWritten()
    }

    /** Its creation sent again after the deletion would bring it back. */
    @Test
    fun `a group a change waits on the server in is refused with nothing sent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        pendingChanges += GROUP

        assertEquals(Result.Error(GroupDeleteError.ChangePending), deleter.delete(GROUP))

        assertRefusedWithNothingWritten()
    }

    @Test
    fun `no such group on this device is refused with nothing sent`() = runBlocking<Unit> {
        assertEquals(Result.Error(GroupDeleteError.NotHeld), deleter.delete(GROUP))

        assertTrue(steps.isEmpty())
    }

    @Test
    fun `nobody signed in is refused with nothing sent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        selfUuid = { null }

        assertEquals(Result.Error(GroupDeleteError.NoSession), deleter.delete(GROUP))

        assertRefusedWithNothingWritten()
    }

    @Test
    fun `a device locked as the deletion waited is refused with nothing sent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        selfUuid = { throw IllegalStateException(DEVICE_LOCKED) }

        assertEquals(Result.Error(GroupDeleteError.DeviceLocked), deleter.delete(GROUP))

        assertRefusedWithNothingWritten()
    }

    /** Never sent later on its own: the user asks again. */
    @Test
    fun `no answer keeps the group as it was`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        answer = Result.Error(NetworkError.NO_INTERNET)

        assertEquals(Result.Error(GroupDeleteError.Network(NetworkError.NO_INTERNET)), deleter.delete(GROUP))

        assertEquals(listOf("delete $GROUP"), steps)
        assertTrue(GROUP in groups)
    }

    /** An earlier request the server took, whose answer was lost. */
    @Test
    fun `a 404 for a group the server lists no more forgets it`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        answer = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(Result.Success(Unit), deleter.delete(GROUP))

        assertFalse(GROUP in groups)
    }

    /** The proxy in front of the server, answering as it is redeployed. */
    @Test
    fun `a 404 for a group the server still lists keeps it`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        answer = Result.Error(NetworkError.NOT_FOUND)
        listing = Result.Success(
            listOf(
                listedGroupOf(
                    uuid = GROUP,
                    ownerUuid = BOB,
                    version = FIELDS.sealedAs(creator = bob, groupKey = ByteArray(32), groupUuid = GROUP),
                    memberUuids = listOf(BOB),
                ),
            ),
        )

        assertEquals(Result.Error(GroupDeleteError.Network(NetworkError.NOT_FOUND)), deleter.delete(GROUP))

        assertTrue(GROUP in groups)
    }

    @Test
    fun `a 404 with no listing to go by keeps the group`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        answer = Result.Error(NetworkError.NOT_FOUND)
        listing = Result.Error(NetworkError.NO_INTERNET)

        assertEquals(Result.Error(GroupDeleteError.Network(NetworkError.NOT_FOUND)), deleter.delete(GROUP))

        assertTrue(GROUP in groups)
    }

    /** Never a final refusal: nothing the user can tell apart from no answer. */
    @Test
    fun `a 403 keeps the group`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        answer = Result.Error(NetworkError.FORBIDDEN)

        assertEquals(Result.Error(GroupDeleteError.Network(NetworkError.FORBIDDEN)), deleter.delete(GROUP))

        assertTrue(GROUP in groups)
    }

    /** Taken by the server all the same: the next sync finds it gone, see [GroupSync]. */
    @Test
    fun `storage failing once the server took it is told`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB)
        isForgetFailing = true

        assertEquals(Result.Error(GroupDeleteError.Storage), deleter.delete(GROUP))
    }

    /** Whatever the device does once the server took it, the rows go. */
    @Test
    fun `a client locked once the group is forgotten is no failure`() = runBlocking<Unit> {
        val mlsGroupId = bobCreates()
        isClientLocked = true

        assertEquals(Result.Success(Unit), deleter.delete(GROUP))

        assertFalse(GROUP in groups)
        assertTrue(bob.holdsGroup(mlsGroupId = mlsGroupId))
    }

    private fun assertRefusedWithNothingWritten() {
        assertTrue(steps.isEmpty())
        assertTrue(GROUP in groups)
    }

    /** Has Bob create [GROUP] with Carol in it on his device, and returns its MLS group's id. */
    private fun bobCreates(): ByteArray {
        val creation = bob.createGroup(
            groupUuid = GROUP,
            invitees = listOf(carol.inviteeKeyPackage()),
            welcomePayload = welcomePayloadOf(profileKeys = emptyMap()),
        )

        groups[GROUP] = groupOf(ownerUuid = BOB, mlsGroupId = creation.mlsGroupId)

        return creation.mlsGroupId
    }

    /** Only this device holds it: nothing to send, whatever the server would say. */
    @Test
    fun `a group the user is no longer in comes off this device with nothing sent`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, isLeft = true)

        assertEquals(Result.Success(Unit), deleter.forgetLeft(GROUP))

        assertEquals(listOf("forget left $GROUP"), steps)
        assertFalse(GROUP in groups)
    }

    /** The copy an owner keeps when the answer to their deletion was lost is theirs to take off too. */
    @Test
    fun `an owner's copy of a group found gone comes off this device`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = BOB, isLeft = true)

        assertEquals(Result.Success(Unit), deleter.forgetLeft(GROUP))

        assertFalse(GROUP in groups)
    }

    /** Its row is what names a state a leave could not delete: gone first, it would be left for good. */
    @Test
    fun `a group coming off this device takes any MLS state left of it before its row`() = runBlocking<Unit> {
        val mlsGroupId = bobCreates()
        groups[GROUP] = groupOf(ownerUuid = ALICE, mlsGroupId = mlsGroupId, isLeft = true)

        assertEquals(Result.Success(Unit), deleter.forgetLeft(GROUP))

        assertEquals(false, wasStateHeldAsForgotten)
        assertFalse(bob.holdsGroup(mlsGroupId = mlsGroupId))
    }

    /** The row of a group the user is out of keeps its id, the state itself being gone already. */
    @Test
    fun `a row naming a state long gone comes off this device`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, mlsGroupId = byteArrayOf(1), isLeft = true)

        assertEquals(Result.Success(Unit), deleter.forgetLeft(GROUP))

        assertFalse(GROUP in groups)
    }

    /** It is all on this device: a server out of reach since the app started keeps nothing from it. */
    @Test
    fun `taking a group off this device needs no client that reads the vault`() = runBlocking<Unit> {
        val mlsGroupId = bobCreates()
        groups[GROUP] = groupOf(ownerUuid = ALICE, mlsGroupId = mlsGroupId, isLeft = true)

        assertEquals(Result.Success(Unit), deleter.forgetLeft(GROUP))

        assertEquals(0, vaultClientOpens)
        assertFalse(bob.holdsGroup(mlsGroupId = mlsGroupId))
        assertFalse(GROUP in groups)
    }

    @Test
    fun `nobody signed in as the client opens keeps the group`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, mlsGroupId = byteArrayOf(1), isLeft = true)
        clientFailure = MlsIdentityError.MissingSession

        assertEquals(Result.Error(GroupDeleteError.NoSession), deleter.forgetLeft(GROUP))

        assertTrue(GROUP in groups)
    }

    @Test
    fun `a client that does not open for any other reason keeps the group`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, mlsGroupId = byteArrayOf(1), isLeft = true)
        clientFailure = MlsIdentityError.Crypto

        assertEquals(Result.Error(GroupDeleteError.Storage), deleter.forgetLeft(GROUP))

        assertTrue(GROUP in groups)
    }

    /** No state to delete, so no client to open: nothing keeps it from going offline. */
    @Test
    fun `a group whose row names no state comes off this device with no client`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, isLeft = true)
        clientFailure = MlsIdentityError.Crypto

        assertEquals(Result.Success(Unit), deleter.forgetLeft(GROUP))

        assertFalse(GROUP in groups)
    }

    /** Its creator added them back as this waited for a sync: it is a group they are in. */
    @Test
    fun `a group the user is in again is refused and kept`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE)

        assertEquals(Result.Error(GroupDeleteError.StillMember), deleter.forgetLeft(GROUP))

        assertTrue(steps.isEmpty())
        assertTrue(GROUP in groups)
    }

    @Test
    fun `taking off a group this device does not hold is refused`() = runBlocking<Unit> {
        assertEquals(Result.Error(GroupDeleteError.NotHeld), deleter.forgetLeft(GROUP))

        assertTrue(steps.isEmpty())
    }

    @Test
    fun `taking a group off with nobody signed in is refused and keeps it`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, isLeft = true)
        selfUuid = { null }

        assertEquals(Result.Error(GroupDeleteError.NoSession), deleter.forgetLeft(GROUP))

        assertTrue(GROUP in groups)
    }

    @Test
    fun `taking a group off on a device locked as it waited is refused and keeps it`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, isLeft = true)
        selfUuid = { throw IllegalStateException(DEVICE_LOCKED) }

        assertEquals(Result.Error(GroupDeleteError.DeviceLocked), deleter.forgetLeft(GROUP))

        assertTrue(GROUP in groups)
    }

    @Test
    fun `storage failing as a group comes off this device is told`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, isLeft = true)
        isForgetFailing = true

        assertEquals(Result.Error(GroupDeleteError.Storage), deleter.forgetLeft(GROUP))

        assertTrue(GROUP in groups)
    }

    @Test
    fun `a client locked as a group comes off this device keeps it`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(ownerUuid = ALICE, mlsGroupId = byteArrayOf(1), isLeft = true)
        isClientLocked = true

        assertEquals(Result.Error(GroupDeleteError.DeviceLocked), deleter.forgetLeft(GROUP))

        assertTrue(GROUP in groups)
    }

    private fun groupOf(
        ownerUuid: String,
        mlsGroupId: ByteArray? = null,
        isLeft: Boolean = false,
    ) = GroupEntity(
        uuid = GROUP,
        title = "Ski trip",
        description = "",
        currency = CurrencyCatalog.defaultCurrency,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = ownerUuid,
        mlsGroupId = mlsGroupId,
        groupKey = ByteArray(32),
        keyGeneration = FIRST_KEY_GENERATION,
        isBackedUp = true,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}
