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
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** What KSafe throws as anything stored with a key a locked device cannot use is read. */
private const val DEVICE_LOCKED = "KSafe: Cannot access Keystore key - device is locked"

/** When the server stored each version, and the same in milliseconds since the epoch. */
private const val CREATED_AT = "2026-10-27T10:00:00.000Z"
private const val FIRST_EDIT_AT = "2026-10-28T19:02:07.004Z"
private const val SECOND_EDIT_AT = "2026-10-29T08:15:42.317Z"
private const val CREATED_AT_MILLIS = 1793095200000L
private const val FIRST_EDIT_AT_MILLIS = 1793214127004L
private const val SECOND_EDIT_AT_MILLIS = 1793261742317L

private val CURRENCY = CurrencyCatalog.defaultCurrencies.last()

/** What Alice created the group with, then edited it to, twice. */
private val CREATED = GroupFields(title = "Ski trip", description = "Chalet and lift passes", currency = CURRENCY)
private val FIRST_EDIT = GroupFields(title = "Ski trip 2027", description = "", currency = CURRENCY)
private val SECOND_EDIT = GroupFields(
    title = "Ski trip 2027",
    description = "Chalet and the train",
    currency = CURRENCY,
)

/**
 * What the history of the group Alice created with Bob and Carol shows, see [GroupHistory], to Bob,
 * who pinned her, unless a test says otherwise, and to Alice herself: every signature made and
 * checked by the library itself, the server answering with the versions a test has it hold. The
 * database is stood in for.
 */
class GroupHistoryTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    private val groupKey = generateSymmetricEncryptionKey()

    /** The versions of the group as the server holds them, each signed by Alice and sealed under its key. */
    private val created = sealed(CREATED, revision = 1).inHistory(revisedAt = CREATED_AT)
    private val firstEdit = sealed(FIRST_EDIT, revision = 2).inHistory(revisedAt = FIRST_EDIT_AT)
    private val secondEdit = sealed(SECOND_EDIT, revision = 3).inHistory(revisedAt = SECOND_EDIT_AT)

    /**
     * The user the session names, Bob's unless a test says otherwise, whose MLS client is the one
     * opened, whether the device is locked as each of the session and the identity is read, and
     * whether the identity opens.
     */
    private var sessionUuid: String? = BOB
    private var isLockedForSession = false
    private var isLockedForIdentity = false
    private var isIdentityOpening = true

    private val groups = mutableMapOf(GROUP to groupOf())

    /** What the server answers for the group's history, and the groups it was asked for. */
    private var answer: Result<GroupHistoryResponse, NetworkError> =
        Result.Success(GroupHistoryResponse(current = secondEdit, previous = listOf(firstEdit, created)))
    private val asked = mutableListOf<String>()

    private val history = GroupHistory(
        userUuid = {
            check(!isLockedForSession) { DEVICE_LOCKED }
            sessionUuid
        },
        group = { groups[it] },
        client = {
            check(!isLockedForIdentity) { DEVICE_LOCKED }

            when {
                !isIdentityOpening -> Result.Error(MlsIdentityError.Crypto)
                sessionUuid == ALICE -> Result.Success(alice)
                else -> Result.Success(bob)
            }
        },
        history = { groupUuid ->
            asked += groupUuid
            answer
        },
    )

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
    }

    @Test
    fun `the history shows every version the creator signed newest first with when each was stored`() =
        runBlocking<Unit> {
            val versions = assertIs<Result.Success<List<GroupInformationVersion>>>(read()).data

            assertEquals(listOf(3L, 2L, 1L), versions.map { it.revision })
            assertEquals(listOf(SECOND_EDIT, FIRST_EDIT, CREATED).map { it.title }, versions.map { it.title })
            assertEquals(
                listOf(SECOND_EDIT, FIRST_EDIT, CREATED).map { it.description },
                versions.map { it.description },
            )
            assertEquals(listOf(false, false, true), versions.map { it.isCreation })
            assertEquals(listOf(true, false, false), versions.map { it.isCurrent })
            assertEquals(
                listOf(SECOND_EDIT_AT_MILLIS, FIRST_EDIT_AT_MILLIS, CREATED_AT_MILLIS),
                versions.map { it.revisedAt },
            )
            assertEquals(listOf(GROUP), asked)
        }

    /** As the version the group shows is, see [GroupSync], whose creator she is. */
    @Test
    fun `the creator reads the history of her own group against her own key`() = runBlocking<Unit> {
        sessionUuid = ALICE

        assertEquals(listOf(3L, 2L, 1L), revisionsRead())
    }

    /** The server's order is nothing the creator signed. */
    @Test
    fun `the versions are shown in the order of their revisions whatever the server lists them in`() =
        runBlocking<Unit> {
            answer = Result.Success(GroupHistoryResponse(current = secondEdit, previous = listOf(created, firstEdit)))

            assertEquals(listOf(3L, 2L, 1L), revisionsRead())
        }

    /** As the version the group shows was never signed by anybody but Alice. */
    @Test
    fun `the history of a group never edited is the version it was created as`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(infoRevision = 1)
        answer = Result.Success(GroupHistoryResponse(current = created, previous = emptyList()))

        val version = assertIs<Result.Success<List<GroupInformationVersion>>>(read()).data.single()

        assertEquals(CREATED.title, version.title)
        assertTrue(version.isCreation)
        assertTrue(version.isCurrent)
    }

    /**
     * Carol holds the group's key, and the server can list what she sealed, as it takes the edits
     * of a member an account deletion handed it over to: she cannot sign as Alice.
     */
    @Test
    fun `a version another member signed is left out`() = runBlocking<Unit> {
        val carols = FIRST_EDIT.sealedAs(creator = carol, groupKey = groupKey, groupUuid = GROUP, revision = 2)

        answer = Result.Success(
            GroupHistoryResponse(current = secondEdit, previous = listOf(carols.inHistory(), created)),
        )

        assertEquals(listOf(3L, 1L), revisionsRead())
    }

    /** Bound to the group it was sealed for, it opens as no other's, even under the same key. */
    @Test
    fun `a version sealed for another group is left out`() = runBlocking<Unit> {
        val elsewhere = FIRST_EDIT.sealedAs(creator = alice, groupKey = groupKey, groupUuid = OTHER_GROUP, revision = 2)

        answer = Result.Success(
            GroupHistoryResponse(current = secondEdit, previous = listOf(elsewhere.inHistory(), created)),
        )

        assertEquals(listOf(3L, 1L), revisionsRead())
    }

    /**
     * One the server kept under a key the group had before, rather than delete it as the rotation
     * that left it out was taken, see [RotateGroupKeyBody.previous].
     */
    @Test
    fun `a version sealed under another key is left out`() = runBlocking<Unit> {
        val underAnotherKey = FIRST_EDIT.sealedAs(
            creator = alice,
            groupKey = generateSymmetricEncryptionKey(),
            groupUuid = GROUP,
            revision = 2,
        )

        answer = Result.Success(
            GroupHistoryResponse(current = secondEdit, previous = listOf(underAnotherKey.inHistory(), created)),
        )

        assertEquals(listOf(3L, 1L), revisionsRead())
    }

    /**
     * A rotation taken since Bob's device last synced: the server sealed every version again under
     * the key it handed over, which his device holds once its sync reads the message handing it to
     * him, and reads the history with then.
     */
    @Test
    fun `a history none of whose versions opens under the key the group holds is unreadable`() =
        runBlocking<Unit> {
            val rotated = generateSymmetricEncryptionKey()

            fun underRotated(fields: GroupFields, revision: Long) =
                fields.sealedAs(creator = alice, groupKey = rotated, groupUuid = GROUP, revision = revision).inHistory()

            answer = Result.Success(
                GroupHistoryResponse(
                    current = underRotated(SECOND_EDIT, 3),
                    previous = listOf(underRotated(FIRST_EDIT, 2), underRotated(CREATED, 1)),
                ),
            )

            assertEquals(Result.Error(GroupHistoryError.Unreadable), read())
        }

    /** Alice's latest edit, which Bob's device shows once its sync lists it. */
    @Test
    fun `a version newer than the one this device shows is the current one`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(infoRevision = 2)

        val versions = assertIs<Result.Success<List<GroupInformationVersion>>>(read()).data

        assertEquals(listOf(true, false, false), versions.map { it.isCurrent })
    }

    /**
     * The server holding back the version Bob's device shows, as it would to have it go back to
     * the one it replaced, see [newerThan]: what is left is the history before it.
     */
    @Test
    fun `no version older than the one this device shows is the current one`() = runBlocking<Unit> {
        answer = Result.Success(GroupHistoryResponse(current = firstEdit, previous = listOf(created)))

        val versions = assertIs<Result.Success<List<GroupInformationVersion>>>(read()).data

        assertEquals(listOf(2L, 1L), versions.map { it.revision })
        assertEquals(listOf(false, false), versions.map { it.isCurrent })
    }

    /**
     * Every version is sealed under the same key with the same contexts, so a title moved from one
     * to another opens all the same: only the signature holds it to its version.
     */
    @Test
    fun `versions whose fields were swapped with each other's are left out`() = runBlocking<Unit> {
        val swapped = listOf(firstEdit.copy(title = created.title), created.copy(title = firstEdit.title))

        answer = Result.Success(GroupHistoryResponse(current = secondEdit, previous = swapped))

        assertEquals(listOf(3L), revisionsRead())
    }

    /** The signature covers the revision, which the server stores beside it unsealed. */
    @Test
    fun `a version listed under another revision is left out`() = runBlocking<Unit> {
        answer = Result.Success(
            GroupHistoryResponse(current = secondEdit, previous = listOf(firstEdit.copy(revision = 4), created)),
        )

        assertEquals(listOf(3L, 1L), revisionsRead())
    }

    /**
     * Alice saved an edit again after its answer was lost, signing it at the revision the server
     * held already, which refused it: the server saw it all the same.
     */
    @Test
    fun `a revision listed twice is shown once`() = runBlocking<Unit> {
        val resaved = SECOND_EDIT.copy(title = "Ski trip, again")
            .let { sealed(it, revision = 3).inHistory(revisedAt = SECOND_EDIT_AT) }

        answer = Result.Success(GroupHistoryResponse(current = secondEdit, previous = listOf(resaved, firstEdit)))

        val versions = assertIs<Result.Success<List<GroupInformationVersion>>>(read()).data

        assertEquals(listOf(3L, 2L), versions.map { it.revision })
        assertEquals(SECOND_EDIT.title, versions.first().title)
    }

    /**
     * A group brought back after a log-out whose creator is not one of Bob's contacts: there is
     * nothing to hold its versions against, as for the version it shows, see [isSignedByCreator].
     */
    @Test
    fun `a creator this device holds no pin for is taken at the versions' word`() = runBlocking<Unit> {
        val unpinned = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
        val carols = FIRST_EDIT.sealedAs(creator = carol, groupKey = groupKey, groupUuid = GROUP, revision = 2)
        val reader = GroupHistory(
            userUuid = { BOB },
            group = { groups[it] },
            client = { Result.Success(unpinned) },
            history = {
                Result.Success(GroupHistoryResponse(current = secondEdit, previous = listOf(carols.inHistory())))
            },
        )

        val versions = assertIs<Result.Success<List<GroupInformationVersion>>>(reader.versions(groupUuid = GROUP)).data

        assertEquals(listOf(3L, 2L), versions.map { it.revision })
    }

    /** The server gives its history to its current members alone, and nothing is asked of it. */
    @Test
    fun `a group the user is no longer in is refused`() = runBlocking<Unit> {
        groups[GROUP] = groupOf(isLeft = true)

        assertEquals(Result.Error(GroupHistoryError.NotMember), read())
        assertTrue(asked.isEmpty())
    }

    /** A creation the server refused takes its group off the device under whatever screen is open. */
    @Test
    fun `a group this device does not hold is refused`() = runBlocking<Unit> {
        groups.clear()

        assertEquals(Result.Error(GroupHistoryError.NotMember), read())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a history the server could not be asked for is a network failure`() = runBlocking<Unit> {
        answer = Result.Error(NetworkError.NO_INTERNET)

        assertEquals(Result.Error(GroupHistoryError.Network(NetworkError.NO_INTERNET)), read())
    }

    /** Bob removed since his device last synced, say, or the proxy answering as the server is redeployed. */
    @Test
    fun `a history the server refuses is a network failure`() = runBlocking<Unit> {
        answer = Result.Error(NetworkError.NOT_FOUND)

        assertEquals(Result.Error(GroupHistoryError.Network(NetworkError.NOT_FOUND)), read())
    }

    /** Who is signed in reads with a key a locked device cannot use. */
    @Test
    fun `a history on a locked device reads nothing`() = runBlocking<Unit> {
        isLockedForSession = true

        assertEquals(Result.Error(GroupHistoryError.DeviceLocked), read())
        assertTrue(asked.isEmpty())
    }

    /** The device locking before this process first opened Bob's identity, read from the vault. */
    @Test
    fun `a history whose identity cannot be read on a locked device reads nothing`() = runBlocking<Unit> {
        isLockedForIdentity = true

        assertEquals(Result.Error(GroupHistoryError.DeviceLocked), read())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a history whose identity does not open reads nothing`() = runBlocking<Unit> {
        isIdentityOpening = false

        assertEquals(Result.Error(GroupHistoryError.Identity(MlsIdentityError.Crypto)), read())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a history with nobody signed in reads nothing`() = runBlocking<Unit> {
        sessionUuid = null

        assertEquals(Result.Error(GroupHistoryError.NoSession), read())
        assertTrue(asked.isEmpty())
    }

    private suspend fun read() = history.versions(groupUuid = GROUP)

    private suspend fun revisionsRead() =
        assertIs<Result.Success<List<GroupInformationVersion>>>(read()).data.map { it.revision }

    /** [fields] as [revision] of the group, signed by Alice and sealed under its key. */
    private fun sealed(fields: GroupFields, revision: Long) =
        fields.sealedAs(creator = alice, groupKey = groupKey, groupUuid = GROUP, revision = revision)

    /**
     * The group as Bob's device, or Alice's, holds it, showing its second edit, or the version
     * [infoRevision] names.
     */
    private fun groupOf(isLeft: Boolean = false, infoRevision: Long = 3) = GroupEntity(
        uuid = GROUP,
        title = SECOND_EDIT.title,
        description = SECOND_EDIT.description,
        currency = CURRENCY,
        infoRevision = infoRevision,
        ownerUuid = ALICE,
        mlsGroupId = GROUP.encodeToByteArray(),
        groupKey = groupKey,
        keyGeneration = FIRST_KEY_GENERATION,
        isBackedUp = true,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}

private fun GroupFields.copy(title: String = this.title) =
    GroupFields(title = title, description = description, currency = currency)
