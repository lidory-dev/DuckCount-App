package group

import database.data.local.entity.GroupEntity
import database.domain.CurrencyCatalog
import kotlinx.coroutines.runBlocking
import message.ResendsBooked
import mls.InMemoryMlsStore
import mls.inviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"

/** Who is in the group, as the server lists it and Bob's device holds it. */
private val MEMBERS = listOf(ALICE, BOB, CAROL)

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/** What the group was created with. */
private val FIELDS = GroupFields(
    title = "Ski trip",
    description = "Chalet and lift passes",
    currency = CurrencyCatalog.defaultCurrency,
)

/** What its creator edited it to. */
private val EDITED = GroupFields(
    title = "Ski trip 2027",
    description = "Chalet, lift passes and the train",
    currency = CurrencyCatalog.defaultCurrency,
)

/** What somebody else would have it show. */
private val FORGED = GroupFields(
    title = "Pay Carol back",
    description = "",
    currency = CurrencyCatalog.defaultCurrency,
)

/**
 * What Bob's group sync shows of the information of a group he holds once the server lists an
 * edit of it, see [GroupSync.sync]: a newer version its creator signed, and nothing else, every
 * signature made and checked by the library itself.
 *
 * Bob holds the group, Alice's unless a test says otherwise, with nothing waiting in it, and Alice
 * pinned as becoming a contact pins her. Carol is in the group too, and holds its key. Dave is
 * nobody Bob's device holds a pin for, as a log-out leaves the creator of a group of somebody's who
 * is no contact of his.
 */
class GroupSyncEditsTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    private val groupKey = generateSymmetricEncryptionKey()

    /** The group as Bob's device holds it, and the version the server lists of it. */
    private val groups = mutableMapOf(GROUP to heldGroup())
    private var serverVersion = FIELDS.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP)

    /** Every version the sync showed, in order. */
    private val shown = mutableListOf<GroupVersion>()

    private val groupSync = GroupSync(
        userUuid = { BOB },
        resendPending = { Result.Success(Unit) },
        resendMembershipChanges = { Result.Success(Unit) },
        // What becomes of the copies of the group keys is GroupKeyCopiesTest's.
        openKeyCopies = { Result.Success(emptyMap()) },
        putUpKeyCopies = { Result.Success(Unit) },
        restoreContacts = { Result.Success(Unit) },
        listGroups = {
            val group = groups.getValue(GROUP)

            Result.Success(
                listOf(
                    listedGroupOf(
                        uuid = GROUP,
                        ownerUuid = group.ownerUuid,
                        version = serverVersion,
                        memberUuids = MEMBERS,
                    ),
                ),
            )
        },
        storedGroups = { groups.values.toList() },
        declinedGroups = { emptyList() },
        listMessages = { Result.Success(ListGroupMessagesResponse(messages = emptyList())) },
        acknowledge = { _, _ -> error("Nothing waits for Bob here.") },
        leave = { error("Bob leaves nothing here.") },
        isUnblockedContact = { it == ALICE },
        client = { Result.Success(bob) },
        save = { _, _, _, _ -> error("Bob joins nothing here.") },
        memberUuidsOf = { MEMBERS },
        replaceMembers = { _, _ -> },
        recordSignatureKeys = {},
        takeKeys = { _, _ -> error("Nobody hands Bob a key here.") },
        updateInformation = { groupUuid, version ->
            shown += version
            groups[groupUuid] = groups.getValue(groupUuid).showing(version)
        },
        markLeft = { error("Nobody removes Bob here.") },
        recordDeclined = { error("Bob declines nothing here.") },
        forgetDeclined = { error("Bob declines nothing here.") },
        removeDeparted = { _, _ -> error("Nobody left a group of Bob's here.") },
        fullName = { null },
        hasPendingChange = { false },
        isBlocked = { false },
        setHandedProfileKey = { _, _ -> },
        takeOwnerKey = { _, _, _ -> },
        fillGap = { _, _ -> },
        restoreMemberProfiles = { Result.Success(Unit) },
        readMemberNames = { Result.Success(Unit) },
        backUpMemberProfiles = { Result.Success(Unit) },
        handOutProfileKey = { Result.Success(Unit) },
        rebuildWakeList = {},
        resendScheduler = ResendsBooked(),
    )

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
    }

    @Test
    fun `a newer version the creator signed is shown`() = runBlocking<Unit> {
        serverVersion = EDITED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShows(EDITED, revision = 2)
    }

    /** Several edits made while Bob's device was away: the last one is what it shows. */
    @Test
    fun `a version several edits newer is shown at once`() = runBlocking<Unit> {
        serverVersion = EDITED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 5)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShows(EDITED, revision = 5)
    }

    /** Carol holds the group's key and can seal what she likes, which the server can list. */
    @Test
    fun `a version another member signed is never shown`() = runBlocking<Unit> {
        serverVersion = FORGED.sealedAs(creator = carol, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShowsWhatItShowed()
    }

    /** Alice's signature over her edit, with a title Carol sealed in place of hers. */
    @Test
    fun `a version with a field its creator did not sign is never shown`() = runBlocking<Unit> {
        val signed = EDITED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)
        val forged = FORGED.sealedAs(creator = carol, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        serverVersion = SealedGroupVersion(
            fields = SealedGroupFields(
                title = forged.fields.title,
                description = signed.fields.description,
                currency = signed.fields.currency,
            ),
            revision = 2,
            signature = signed.signature,
        )

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShowsWhatItShowed()
    }

    /** The server listing Alice's edit under a revision she did not sign it as, to have it outrank a later one. */
    @Test
    fun `a version listed under another revision than its creator signed is never shown`() = runBlocking<Unit> {
        val signed = EDITED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        serverVersion = SealedGroupVersion(fields = signed.fields, revision = 7, signature = signed.signature)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShowsWhatItShowed()
    }

    /** The server holding Alice's last edit back, to have Bob's device show one she replaced. */
    @Test
    fun `an older version is never gone back to`() = runBlocking<Unit> {
        groups[GROUP] = heldGroup(fields = EDITED, infoRevision = 3)
        serverVersion = FIELDS.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShows(EDITED, revision = 3)
        assertTrue(shown.isEmpty())
    }

    /** The version shown, as a key rotation seals it again: nothing to show anew. */
    @Test
    fun `the version shown listed again is left as it is`() = runBlocking<Unit> {
        groups[GROUP] = heldGroup(fields = EDITED, infoRevision = 2)
        serverVersion = EDITED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShows(EDITED, revision = 2)
        assertTrue(shown.isEmpty())
    }

    /** Whatever its contents: one revision is one version, and the one shown is the creator's. */
    @Test
    fun `another version under the revision shown is never shown`() = runBlocking<Unit> {
        serverVersion = FORGED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 1)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShowsWhatItShowed()
    }

    /** One sealed under a key Bob was never handed, as a removal of his would leave it. */
    @Test
    fun `a version that does not open under the group's key is never shown`() = runBlocking<Unit> {
        serverVersion = EDITED.sealedAs(
            creator = alice,
            groupKey = generateSymmetricEncryptionKey(),
            groupUuid = GROUP,
            revision = 2,
        )

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShowsWhatItShowed()
    }

    /** An edit of Bob's own whose answer was lost, which the server took: checked under his own key. */
    @Test
    fun `a group the user created shows an edit of theirs the server took`() = runBlocking<Unit> {
        groups[GROUP] = heldGroup(ownerUuid = BOB, mlsGroupId = bobCreates())
        serverVersion = EDITED.sealedAs(creator = bob, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShows(EDITED, revision = 2)
    }

    @Test
    fun `a group the user created shows no version anybody else signed`() = runBlocking<Unit> {
        groups[GROUP] = heldGroup(ownerUuid = BOB, mlsGroupId = bobCreates())
        serverVersion = FORGED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShowsWhatItShowed()
    }

    /** As a log-out leaves a group of Alice's: no MLS state, the key its copy held. */
    @Test
    fun `a group brought back after a log-out shows a newer version its creator signed`() = runBlocking<Unit> {
        groups[GROUP] = heldGroup(mlsGroupId = null)
        serverVersion = EDITED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShows(EDITED, revision = 2)
    }

    /**
     * Dave's group, brought back after a log-out with no pin for him: there is nothing to hold the
     * version against, and it is taken at its word, whoever signed it.
     */
    @Test
    fun `a version of a group whose creator this device holds no pin for is taken at its word`() =
        runBlocking<Unit> {
            groups[GROUP] = heldGroup(ownerUuid = DAVE, mlsGroupId = null)
            serverVersion = FORGED.sealedAs(creator = carol, groupKey = groupKey, groupUuid = GROUP, revision = 2)

            assertNull(groupSync.sync(contactsTakenIn = true).failure)

            assertShows(FORGED, revision = 2)
        }

    /** One the user was removed from, or left, stays read-only, with what they had of it. */
    @Test
    fun `a group the user is no longer in shows what it showed`() = runBlocking<Unit> {
        groups[GROUP] = heldGroup(isLeft = true)
        serverVersion = EDITED.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        assertNull(groupSync.sync(contactsTakenIn = true).failure)

        assertShowsWhatItShowed()
    }

    /**
     * Has Bob create the group with Alice and Carol, as [GroupCreator] does, and returns its MLS
     * group: his sync checks it for members the server no longer lists, see
     * [GroupSyncDepartedMembersTest], and finds none.
     */
    private fun bobCreates(): ByteArray = bob.createGroup(
        groupUuid = GROUP,
        invitees = listOf(alice.inviteeKeyPackage(), carol.inviteeKeyPackage()),
        welcomePayload = welcomePayloadOf(
            groupKey = groupKey,
            keyGeneration = FIRST_KEY_GENERATION,
            profileKeys = emptyMap(),
        ),
    ).mlsGroupId

    /** That the group shows [fields] as [revision], its currency the one it was created in. */
    private fun assertShows(fields: GroupFields, revision: Long) {
        val group = groups.getValue(GROUP)

        assertEquals(fields.title, group.title)
        assertEquals(fields.description, group.description)
        assertEquals(FIELDS.currency, group.currency)
        assertEquals(revision, group.infoRevision)
    }

    /** That the sync showed nothing new: the group as it was created. */
    private fun assertShowsWhatItShowed() {
        assertShows(FIELDS, revision = FIRST_GROUP_REVISION)
        assertTrue(shown.isEmpty())
    }

    /** The group as Bob's device holds it, created by [ownerUuid], showing [fields] as [infoRevision]. */
    private fun heldGroup(
        ownerUuid: String = ALICE,
        fields: GroupFields = FIELDS,
        infoRevision: Long = FIRST_GROUP_REVISION,
        mlsGroupId: ByteArray? = GROUP.encodeToByteArray(),
        isLeft: Boolean = false,
    ) = GroupEntity(
        uuid = GROUP,
        title = fields.title,
        description = fields.description,
        currency = fields.currency,
        infoRevision = infoRevision,
        ownerUuid = ownerUuid,
        mlsGroupId = mlsGroupId,
        groupKey = groupKey,
        keyGeneration = FIRST_KEY_GENERATION,
        isBackedUp = true,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}
