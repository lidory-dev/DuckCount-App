package group

import database.AppDatabase
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.domain.ExpenseWithBeneficiaries
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import mls.InMemoryMlsStore
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

private const val DINNER = "eeeeeeee-eeee-8eee-8eee-eeeeeeeeeeee"

/**
 * What the history of a group expense tells on Alice's device, see [GroupExpenseHistory]: the
 * version it shows, then those the server kept from before it, each opened under its own
 * generation of the group's key and checked against whoever signed it, newest first, and none from
 * after the one shown. Bob, a contact of hers, and Carol, whose leaf the group's tree held, write
 * them. The database is the real one, in memory, and so are the seals, signatures and links; the
 * server is stood in for.
 */
class GroupExpenseHistoryTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    private val firstKey = generateSymmetricEncryptionKey()
    private val secondKey = generateSymmetricEncryptionKey()

    private val server = GroupExpenseServer()

    /** The links back through the group's keys the server lists, and whether it can be reached for them. */
    private val links = mutableListOf<GroupKeyLinkDto>()
    private var areLinksReachable = true

    /** Bob paid 90.00 for dinner, 30.00 of it each for the three of them. */
    private val dinner = groupExpenseOf(
        uuid = DINNER,
        groupUuid = GROUP,
        creditorUuid = BOB,
        amount = "90.00",
        shares = listOf(ALICE to "30.00", BOB to "30.00", CAROL to "30.00"),
    )

    init {
        alice.pinIdentity(userUuid = BOB, signatureKey = bob.signaturePublicKey())
    }

    private fun historyOf(database: AppDatabase) = GroupExpenseHistory(
        userUuid = { ALICE },
        shown = database.expenseDao::getWithBeneficiaries,
        group = database.groupDao::get,
        client = { Result.Success(alice) },
        history = { _, expenseUuid -> server.history(expenseUuid = expenseUuid) },
        keyOf = GroupKeyChain(
            keyLinks = {
                if (areLinksReachable) Result.Success(links.toList()) else Result.Error(NetworkError.NO_INTERNET)
            },
            group = database.groupDao::get,
            heldKeys = database.groupDao::getKeys,
            storeRecovered = database.groupDao::insertRecoveredKeys,
        )::keyOf,
        recorded = database.groupDao::getMemberSignatureKey,
    )

    /** Alice's device holding the group at [groupKey], of [keyGeneration] and no older one, Carol's leaf read in its tree. */
    private suspend fun AppDatabase.holdingTheGroup(groupKey: ByteArray = firstKey, keyGeneration: Long = FIRST_KEY_GENERATION) {
        groupDao.upsertWithMembers(
            group = groupOf(groupKey = groupKey, keyGeneration = keyGeneration),
            members = emptyList(),
            olderKeys = emptyList(),
            signatureKeys = listOf(
                GroupMemberSignatureKeyEntity(groupUuid = GROUP, userUuid = CAROL, signatureKey = carol.signaturePublicKey()),
            ),
        )
    }

    /** Alice's device showing [expense] as [revision] of it, written by [authorUuid], as a sync stores it. */
    private suspend fun AppDatabase.showing(expense: ExpenseWithBeneficiaries, revision: Long, authorUuid: String) {
        expenseDao.storeGroupExpenses(
            groupUuid = GROUP,
            expenses = listOf(OpenedGroupExpense(expense = expense, revision = revision, authorUuid = authorUuid).toEntity()),
        )
    }

    /** Bob created it, Carol edited it, and Bob edited it again, the version Alice's device shows. */
    @Test
    fun `the versions before the one shown are told newest first with who wrote each`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val edited = dinner.copy(title = "Dinner")
            val shown = dinner.copy(title = "Harbour dinner")

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(edited.listedAs(revision = 2, author = carol, authorUuid = CAROL, groupKey = firstKey))
            server.put(shown.listedAs(revision = 3, author = bob, authorUuid = BOB, groupKey = firstKey))
            database.showing(expense = shown, revision = 3, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(historyOf(database).versions(DINNER)).data

            assertEquals(listOf(3L, 2L, 1L), versions.map { it.revision })
            assertEquals(listOf(BOB, CAROL, BOB), versions.map { it.authorUuid })
            assertEquals(listOf("Harbour dinner", "Dinner", "Dinner at the harbour"), versions.map { it.expense.title })
            assertEquals(listOf(false, false, true), versions.map { it.isCreation })
            assertNotNull(versions.last().madeAt)
            assertNull(versions.first().madeAt)
        }
    }

    /**
     * The server holds two versions Alice's device has yet to catch up with, or refused: neither is
     * told, and the one shown is told once, ahead of what came before it.
     */
    @Test
    fun `no version from after the one shown is told`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val shown = dinner.copy(title = "Dinner")

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(shown.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(dinner.copy(title = "Taxi").listedAs(revision = 3, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(dinner.copy(title = "Hotel").listedAs(revision = 4, author = bob, authorUuid = BOB, groupKey = firstKey))
            database.showing(expense = shown, revision = 2, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(historyOf(database).versions(DINNER)).data

            assertEquals(listOf(2L, 1L), versions.map { it.revision })
            assertEquals(listOf("Dinner", "Dinner at the harbour"), versions.map { it.expense.title })
        }
    }

    /** As Carol writing a version and passing it off as Bob's, which history would show as his. */
    @Test
    fun `a version signed by somebody else than the member it names is left out`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val shown = dinner.copy(title = "Dinner")

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(dinner.copy(title = "Taxi").listedAs(revision = 2, author = carol, authorUuid = BOB, groupKey = firstKey))
            server.put(shown.listedAs(revision = 3, author = bob, authorUuid = BOB, groupKey = firstKey))
            database.showing(expense = shown, revision = 3, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(historyOf(database).versions(DINNER)).data

            assertEquals(listOf(3L, 1L), versions.map { it.revision })
        }
    }

    /**
     * A device holding the group's second key alone, as one that joined after the rotation does:
     * the version sealed under the first is read through the link back to it.
     */
    @Test
    fun `a version sealed under an older key is read through the chain`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup(groupKey = secondKey, keyGeneration = 2)
            links += GroupKeyLinkDto(
                generation = 2,
                previousKey = assertNotNull(sealKeyLink(previousKey = firstKey, groupKey = secondKey, groupUuid = GROUP, generation = 2)),
            )

            val shown = dinner.copy(title = "Dinner")

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(shown.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = secondKey, keyGeneration = 2))
            database.showing(expense = shown, revision = 2, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(historyOf(database).versions(DINNER)).data

            assertEquals(listOf(2L, 1L), versions.map { it.revision })
            assertEquals("Dinner at the harbour", versions.last().expense.title)
        }
    }

    /** The version shown is all there is: the server gives the history to current members alone. */
    @Test
    fun `a group the user is no longer in has no history`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            database.showing(expense = dinner, revision = 1, authorUuid = BOB)
            database.groupDao.markLeft(uuid = GROUP)

            assertEquals(Result.Error(GroupExpenseHistoryError.NotMember), historyOf(database).versions(DINNER))
        }
    }

    /** Told as the failure it is, rather than as a history short of the versions under the older key. */
    @Test
    fun `links that cannot be read from the server are a network failure`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup(groupKey = secondKey, keyGeneration = 2)
            areLinksReachable = false

            val shown = dinner.copy(title = "Dinner")

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(shown.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = secondKey, keyGeneration = 2))
            database.showing(expense = shown, revision = 2, authorUuid = BOB)

            assertEquals(
                Result.Error(GroupExpenseHistoryError.Network(NetworkError.NO_INTERNET)),
                historyOf(database).versions(DINNER),
            )
        }
    }

    private fun groupOf(groupKey: ByteArray, keyGeneration: Long) = GroupEntity(
        uuid = GROUP,
        title = "Ski trip",
        description = "",
        currency = GROUP_CURRENCY,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = BOB,
        mlsGroupId = GROUP.encodeToByteArray(),
        groupKey = groupKey,
        keyGeneration = keyGeneration,
        isBackedUp = true,
        isLeft = false,
        handedProfileKeyHash = null,
    )
}
