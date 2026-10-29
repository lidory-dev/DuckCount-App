package group

import database.data.local.entity.GroupEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import mls.InMemoryMlsStore
import mls.MlsIdentityError
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/** What KSafe throws as anything stored with a key a locked device cannot use is read. */
private const val DEVICE_LOCKED = "KSafe: Cannot access Keystore key - device is locked"

/** What the group was created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrencies.last(),
)

/** What Alice edits it to. */
private const val TITLE = "Ski trip 2027"
private const val DESCRIPTION = "Chalet, lift passes and the train"

/** An edit of Alice's the server took, whose answer never reached her device. */
private val LOST = GroupFields(
    title = "Ski trip, again",
    description = "",
    currency = FIELDS.currency,
)

/**
 * What becomes of an edit Alice makes to the group she created, see [GroupEditor], whatever the
 * server answers, and what Bob, a member who pinned her, makes of what it sends: every signature
 * made and checked by the library itself. The server takes an edit as the API does, only when it
 * comes after the revision it holds, unless a test has it answer otherwise. The database is stood
 * in for.
 */
class GroupEditorTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())

    private val groupKey = generateSymmetricEncryptionKey()

    /**
     * The user the session names, Alice's unless a test says otherwise, whether the device is
     * locked as each of the session and Alice's identity is read, and whether her identity opens.
     */
    private var sessionUuid: String? = ALICE
    private var isLockedForSession = false
    private var isLockedForIdentity = false
    private var isIdentityOpening = true

    private val groups = mutableMapOf(GROUP to aliceGroup())
    private var hasPendingChange = false

    /** The version the server holds, what it answers when a test has it fail, and every edit it was sent. */
    private var serverVersion = FIELDS.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP)
    private var failure: NetworkError? = null
    private val sent = mutableListOf<EditGroupBody>()

    /** Whether the server can be asked for the groups it lists. */
    private var isListingReachable = true

    private val editor = GroupEditor(
        userUuid = {
            check(!isLockedForSession) { DEVICE_LOCKED }
            sessionUuid
        },
        group = { groups[it] },
        hasPendingChange = { hasPendingChange },
        client = {
            check(!isLockedForIdentity) { DEVICE_LOCKED }
            if (isIdentityOpening) Result.Success(alice) else Result.Error(MlsIdentityError.Crypto)
        },
        send = { _, body ->
            sent += body

            when {
                failure != null -> Result.Error(checkNotNull(failure))

                body.revision <= serverVersion.revision -> Result.Error(NetworkError.CONFLICT)

                else -> {
                    serverVersion = serverVersion.editedBy(body)

                    Result.Success(Unit)
                }
            }
        },
        listGroups = {
            if (isListingReachable) {
                Result.Success(
                    listOf(
                        listedGroupOf(
                            uuid = GROUP,
                            ownerUuid = ALICE,
                            version = serverVersion,
                            memberUuids = listOf(ALICE, BOB),
                        ),
                    ),
                )
            } else {
                Result.Error(NetworkError.NO_INTERNET)
            }
        },
        updateInformation = { groupUuid, version ->
            groups[groupUuid] = groups.getValue(groupUuid).showing(version)
        },
    )

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
    }

    @Test
    fun `the creator's edit is shown once the server takes it`() = runBlocking<Unit> {
        assertEquals(Result.Success(Unit), edit())

        val group = groups.getValue(GROUP)

        assertEquals(TITLE, group.title)
        assertEquals(DESCRIPTION, group.description)
        assertEquals(FIELDS.currency, group.currency)
        assertEquals(2L, group.infoRevision)
    }

    /** What Bob's sync opens it to, and checks against the key he pinned for Alice, see [GroupSync]. */
    @Test
    fun `an edit is signed by the creator as the next revision with the group's currency`() = runBlocking<Unit> {
        edit()

        val body = sent.single()

        assertEquals(2L, body.revision)

        val version = assertNotNull(serverVersion.unseal(groupKey = groupKey, groupUuid = GROUP))

        assertEquals(TITLE, version.fields.title)
        assertEquals(DESCRIPTION, version.fields.description)
        assertEquals(FIELDS.currency, version.fields.currency)
        assertEquals(2L, version.revision)
        assertTrue(
            version.isSignedBy(
                ownerUuid = ALICE,
                ownerSignatureKey = assertNotNull(bob.pinnedSignatureKey(userUuid = ALICE)),
                groupUuid = GROUP,
            ),
        )
    }

    @Test
    fun `each edit comes after the one before`() = runBlocking<Unit> {
        edit()
        assertEquals(Result.Success(Unit), edit(title = "Ski trip 2028"))

        assertEquals(listOf(2L, 3L), sent.map { it.revision })
        assertEquals(3L, groups.getValue(GROUP).infoRevision)
        assertEquals("Ski trip 2028", groups.getValue(GROUP).title)
    }

    /** Only its creator's versions are shown by its members, see [GroupSync]. */
    @Test
    fun `a member who did not create the group cannot edit it`() = runBlocking<Unit> {
        sessionUuid = BOB

        assertEquals(Result.Error(GroupEditError.NotCreator), edit())

        assertTrue(sent.isEmpty())
        assertShowsWhatItShowed()
    }

    /**
     * An edit of Alice's the server took whose answer was lost, and another one she makes since:
     * hers is refused as outdated, and the one the server holds shown, for the next to be made
     * over it.
     */
    @Test
    fun `an edit the server finds outdated shows the version it holds`() = runBlocking<Unit> {
        serverVersion = LOST.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertEquals(Result.Error(GroupEditError.Outdated), edit())

        val group = groups.getValue(GROUP)

        assertEquals(LOST.title, group.title)
        assertEquals(LOST.description, group.description)
        assertEquals(2L, group.infoRevision)

        assertEquals(Result.Success(Unit), edit())

        assertEquals(listOf(2L, 3L), sent.map { it.revision })
        assertEquals(TITLE, groups.getValue(GROUP).title)
    }

    /**
     * The server took Alice's edit and its answer was lost, the form still holding what she typed:
     * saved again, it is refused as outdated by the very version it is, which is then shown, and
     * nothing more is sent, which would wake every member again for nothing.
     */
    @Test
    fun `an edit saved again once the server took it unanswered is made without another revision`() =
        runBlocking<Unit> {
            serverVersion = GroupFields(title = TITLE, description = DESCRIPTION, currency = FIELDS.currency)
                .sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

            assertEquals(Result.Success(Unit), edit())

            val group = groups.getValue(GROUP)

            assertEquals(TITLE, group.title)
            assertEquals(DESCRIPTION, group.description)
            assertEquals(2L, group.infoRevision)
            assertEquals(listOf(2L), sent.map { it.revision })
            assertEquals(2L, serverVersion.revision)
        }

    /** As the sync shows only what Alice signed, see [GroupSync]. */
    @Test
    fun `an edit found outdated shows nothing the creator did not sign`() = runBlocking<Unit> {
        serverVersion = LOST.sealedAs(creator = bob, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertEquals(Result.Error(GroupEditError.Outdated), edit())

        assertShowsWhatItShowed()
    }

    /** The next sync shows it, as it does any edit the server holds. */
    @Test
    fun `an edit found outdated with no listing to read shows what it showed`() = runBlocking<Unit> {
        serverVersion = LOST.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)
        isListingReachable = false

        assertEquals(Result.Error(GroupEditError.Outdated), edit())

        assertShowsWhatItShowed()
    }

    @Test
    fun `an edit the server could not be asked about changes nothing`() = runBlocking<Unit> {
        failure = NetworkError.NO_INTERNET

        assertEquals(Result.Error(GroupEditError.Network(NetworkError.NO_INTERNET)), edit())

        assertShowsWhatItShowed()
    }

    /** The server's limit on the changes one user makes to groups, edits among them. */
    @Test
    fun `an edit the server refuses changes nothing`() = runBlocking<Unit> {
        failure = NetworkError.TOO_MANY_REQUESTS

        assertEquals(Result.Error(GroupEditError.Network(NetworkError.TOO_MANY_REQUESTS)), edit())

        assertShowsWhatItShowed()
    }

    /**
     * Its creation, or a change to its members, still on its way: the rotation after a removal
     * would put back the version an edit replaced.
     */
    @Test
    fun `a group with a change waiting on the server is not edited`() = runBlocking<Unit> {
        hasPendingChange = true

        assertEquals(Result.Error(GroupEditError.ChangePending), edit())

        assertTrue(sent.isEmpty())
        assertShowsWhatItShowed()
    }

    /** As the edit waited for a sync under way, say: who is signed in reads with a key a locked device cannot use. */
    @Test
    fun `an edit on a locked device sends nothing`() = runBlocking<Unit> {
        isLockedForSession = true

        assertEquals(Result.Error(GroupEditError.DeviceLocked), edit())

        assertTrue(sent.isEmpty())
        assertShowsWhatItShowed()
    }

    /** The device locking before this process first opened Alice's identity, read from the vault. */
    @Test
    fun `an edit whose identity cannot be read on a locked device sends nothing`() = runBlocking<Unit> {
        isLockedForIdentity = true

        assertEquals(Result.Error(GroupEditError.DeviceLocked), edit())

        assertTrue(sent.isEmpty())
        assertShowsWhatItShowed()
    }

    @Test
    fun `an edit with nobody signed in sends nothing`() = runBlocking<Unit> {
        sessionUuid = null

        assertEquals(Result.Error(GroupEditError.NoSession), edit())

        assertTrue(sent.isEmpty())
    }

    /** A creation the server refused takes its group off the device under whatever screen is open. */
    @Test
    fun `a group this device no longer holds is not edited`() = runBlocking<Unit> {
        groups.clear()

        assertEquals(Result.Error(GroupEditError.NotHeld), edit())

        assertTrue(sent.isEmpty())
    }

    /** The version is signed with Alice's identity, which outlives a log-out, unlike the group's MLS state. */
    @Test
    fun `a group brought back after a log-out is edited as well`() = runBlocking<Unit> {
        groups[GROUP] = aliceGroup(mlsGroupId = null)

        assertEquals(Result.Success(Unit), edit())

        assertEquals(TITLE, groups.getValue(GROUP).title)
    }

    @Test
    fun `an edit whose identity does not open sends nothing`() = runBlocking<Unit> {
        isIdentityOpening = false

        assertEquals(Result.Error(GroupEditError.Identity(MlsIdentityError.Crypto)), edit())

        assertTrue(sent.isEmpty())
        assertShowsWhatItShowed()
    }

    private suspend fun edit(title: String = TITLE, description: String = DESCRIPTION) =
        editor.edit(groupUuid = GROUP, title = title, description = description)

    /** That the group shows what it was created with. */
    private fun assertShowsWhatItShowed() {
        val group = groups.getValue(GROUP)

        assertEquals(FIELDS.title, group.title)
        assertEquals(FIELDS.description, group.description)
        assertEquals(FIRST_GROUP_REVISION, group.infoRevision)
    }

    /** Alice's group as her device holds it, showing what it was created with. */
    private fun aliceGroup(mlsGroupId: ByteArray? = GROUP.encodeToByteArray()) = GroupEntity(
        uuid = GROUP,
        title = FIELDS.title,
        description = FIELDS.description,
        currency = FIELDS.currency,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = ALICE,
        mlsGroupId = mlsGroupId,
        groupKey = groupKey,
        isBackedUp = true,
        isLeft = false,
        handedProfileKeyHash = null,
    )
}
