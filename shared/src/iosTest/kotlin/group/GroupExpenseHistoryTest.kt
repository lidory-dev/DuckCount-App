package group

import database.AppDatabase
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.domain.ExpenseWithBeneficiaries
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
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
 * generation of the group's key and checked against whoever signed it, newest first, none from
 * after the one shown, and when each was made. Bob, a contact of hers, and Carol, whose leaf the
 * group's tree held, write them. The database is the real one, in memory, and so are the seals,
 * signatures and links; the server is stood in for.
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

    private fun historyOf(database: AppDatabase, timeZone: TimeZone = TimeZone.UTC) = GroupExpenseHistory(
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
        timeZone = { timeZone },
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
        }
    }

    /**
     * Bob created it on the evening of the 30th, Carol edited it the next morning, and Bob that night,
     * the version Alice's device shows, which is the one the expense stands at.
     */
    @Test
    fun `each update is made when the server took it told in the time zone given`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val edited = dinner.copy(title = "Dinner")
            val shown = dinner.copy(title = "Harbour dinner")

            server.put(
                dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "2026-10-30T17:50:00.000Z",
            )
            server.put(
                edited.listedAs(revision = 2, author = carol, authorUuid = CAROL, groupKey = firstKey),
                revisedAt = "2026-10-31T09:15:42.317Z",
            )
            server.put(
                shown.listedAs(revision = 3, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "2026-10-31T22:30:00.000Z",
            )
            database.showing(expense = shown, revision = 3, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(
                historyOf(database, timeZone = UtcOffset(hours = 2).asTimeZone()).versions(DINNER),
            ).data

            assertEquals(
                listOf(
                    LocalDateTime(2026, 11, 1, 0, 30),
                    LocalDateTime(2026, 10, 31, 11, 15, 42, 317_000_000),
                    LocalDateTime(2026, 10, 30, 19, 45),
                ),
                versions.map { it.madeAt },
            )
        }
    }

    /** Bob edited it twice since the version Alice's device shows, which it has yet to catch up with. */
    @Test
    fun `the version shown is made when the server took it though the expense was updated since`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val shown = dinner.copy(title = "Dinner")

            server.put(
                dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "2026-10-30T17:50:00.000Z",
            )
            server.put(
                shown.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "2026-10-31T09:15:00.000Z",
            )
            server.put(
                dinner.copy(title = "Taxi").listedAs(revision = 3, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "2026-10-31T10:00:00.000Z",
            )
            server.put(
                dinner.copy(title = "Hotel").listedAs(revision = 4, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "2026-10-31T11:00:00.000Z",
            )
            database.showing(expense = shown, revision = 2, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(historyOf(database).versions(DINNER)).data

            assertEquals(listOf(2L, 1L), versions.map { it.revision })
            assertEquals(LocalDateTime(2026, 10, 31, 9, 15), versions.first().madeAt)
        }
    }

    /**
     * Bob edited it ten times since the version Alice's device shows: the server keeps that one no
     * longer, nor any from before it.
     */
    @Test
    fun `the version shown is made at no known time once the server keeps it no longer`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val shown = dinner.copy(title = "Dinner")

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(shown.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))
            (3L..12L).forEach { revision ->
                server.put(
                    dinner.copy(title = "Dinner $revision").listedAs(revision = revision, author = bob, authorUuid = BOB, groupKey = firstKey),
                )
            }
            database.showing(expense = shown, revision = 2, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(historyOf(database).versions(DINNER)).data

            assertEquals(listOf(2L), versions.map { it.revision })
            assertNull(versions.single().madeAt)
        }
    }

    /** Written with no time zone, as Bob's clock read it: whenever the server took it, and wherever Alice is. */
    @Test
    fun `the expense as it was created is made when its own date says`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(
                dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "2026-11-02T08:00:00.000Z",
            )
            database.showing(expense = dinner, revision = 1, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(
                historyOf(database, timeZone = UtcOffset(hours = -4).asTimeZone()).versions(DINNER),
            ).data

            assertEquals(LocalDateTime(2026, 10, 30, 19, 45), versions.single().madeAt)
        }
    }

    /** What the server says is its word alone, and an instant parses further either way than a date can be written for. */
    @Test
    fun `an update the server took at no time a date can be written for is made at no known time`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val edited = dinner.copy(title = "Dinner")
            val shown = dinner.copy(title = "Harbour dinner")

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(
                edited.listedAs(revision = 2, author = carol, authorUuid = CAROL, groupKey = firstKey),
                revisedAt = "yesterday evening",
            )
            server.put(
                shown.listedAs(revision = 3, author = bob, authorUuid = BOB, groupKey = firstKey),
                revisedAt = "+1000000000-06-01T00:00:00Z",
            )
            database.showing(expense = shown, revision = 3, authorUuid = BOB)

            val versions = assertIs<Result.Success<List<GroupExpenseHistoryEntry>>>(
                historyOf(database, timeZone = UtcOffset(hours = 2).asTimeZone()).versions(DINNER),
            ).data

            assertEquals(listOf(null, null, LocalDateTime(2026, 10, 30, 19, 45)), versions.map { it.madeAt })
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
