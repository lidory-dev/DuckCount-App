package group

import database.AppDatabase
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.withInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mls.InMemoryMlsStore
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

private const val DINNER = "eeeeeeee-eeee-8eee-8eee-eeeeeeeeeeee"
private const val TAXI = "dddddddd-dddd-8ddd-8ddd-dddddddddddd"
private const val PAID_BACK = "cccccccc-cccc-8ccc-8ccc-cccccccccccc"

/**
 * What Alice's device makes of the expenses of a group she is in, see [GroupExpenseSync]: Bob and
 * Carol write them, as the server lists them, and her device lists the group whole once, then
 * follows its feed, and lists it whole again when the feed starts over. The database is the real
 * one, in memory, and so are the seals and signatures; the server is stood in for.
 */
class GroupExpenseSyncTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())

    private val firstKey = generateSymmetricEncryptionKey()
    private val secondKey = generateSymmetricEncryptionKey()

    private val server = GroupExpenseServer()

    /** Bob paid 90.00 for dinner, 30.00 of it each for the three of them. */
    private val dinner = groupExpenseOf(
        uuid = DINNER,
        groupUuid = GROUP,
        creditorUuid = BOB,
        amount = "90.00",
        shares = listOf(ALICE to "30.00", BOB to "30.00", CAROL to "30.00"),
    )

    private val taxi = groupExpenseOf(
        uuid = TAXI,
        groupUuid = GROUP,
        creditorUuid = CAROL,
        amount = "20.00",
        shares = listOf(ALICE to "10.00", CAROL to "10.00"),
        title = "Taxi",
    )

    init {
        // Bob is a contact of Alice's; Carol is not, and is held to the key the group's tree held.
        alice.pinIdentity(userUuid = BOB, signatureKey = bob.signaturePublicKey())
    }

    private fun syncOf(
        database: AppDatabase,
        listExpenses: suspend (groupUuid: String) -> Result<ListGroupExpensesResponse, NetworkError> = {
            server.getExpenses()
        },
        keyOf: suspend (
            groupUuid: String,
            generation: Long,
            opens: (key: ByteArray) -> Boolean,
        ) -> Result<ByteArray, GroupKeyChainError> = GroupKeyChain(
            keyLinks = { Result.Success(emptyList()) },
            group = database.groupDao::get,
            heldKeys = database.groupDao::getKeys,
            storeRecovered = database.groupDao::insertRecoveredKeys,
        )::keyOf,
    ) = GroupExpenseSync(
        userUuid = { ALICE },
        client = { Result.Success(alice) },
        listExpenses = listExpenses,
        listExpensesFrom = { _, cursor -> server.getExpensesFrom(cursor = cursor) },
        listChanges = { _, since -> server.getChanges(since = since) },
        keyOf = keyOf,
        groupDao = database.groupDao,
        expenseDao = database.expenseDao,
        listedDao = database.listedGroupExpenseDao,
    )

    /** Alice's device holding the group at its first key, Carol's leaf read in its tree. */
    private suspend fun AppDatabase.holdingTheGroup(isLeft: Boolean = false) {
        groupDao.upsertWithMembers(
            group = groupOf(isLeft = isLeft),
            members = emptyList(),
            keys = listOf(groupOf(isLeft = isLeft).currentKey()),
            signatureKeys = listOf(
                GroupMemberSignatureKeyEntity(groupUuid = GROUP, userUuid = CAROL, signatureKey = carol.signaturePublicKey()),
            ),
        )
    }

    @Test
    fun `the first listing is read whole and every version in it shown`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = firstKey))

            val pass = syncOf(database).sync()

            assertNull(pass.failure)
            assertEquals(setOf(DINNER, TAXI), pass.changes.map { it.expense.uuid }.toSet())
            assertTrue(pass.changes.all { it.isFirstListing && it.previous == null })

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER))

            assertEquals(1L, shown.expense.revision)
            assertEquals(BOB, shown.expense.authorUuid)
            assertEquals(3, shown.beneficiaries.size)
            assertNotNull(database.listedGroupExpenseDao.getFeedToken(groupUuid = GROUP))
            assertEquals(1, server.listings)
        }
    }

    /** Bob's edit, read from the feed the first listing made: nothing is listed whole again. */
    @Test
    fun `what changed since is read from the group's feed`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            val edited = dinner.copy(
                amount = "60.00",
                beneficiaries = dinner.beneficiaries.filter { it.userUuid != CAROL }.map { it.copy(share = "30.00") },
            )

            server.put(edited.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))

            val pass = sync.sync()
            val change = pass.changes.single()

            assertEquals(2L, change.revision)
            assertEquals(BOB, change.authorUuid)
            assertEquals("90.00", assertNotNull(change.previous).amount)
            assertFalse(change.isFirstListing)
            assertEquals(1, server.listings)

            // The edit dropped Carol, whose row goes with it.
            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER))

            assertEquals(setOf(ALICE, BOB), shown.beneficiaries.map { it.userUuid }.toSet())
        }
    }

    /** The feed started over: too many changes waited in it, say, or a week went by. */
    @Test
    fun `the listing is read whole again once the feed starts over`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            server.put(taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = firstKey))
            server.restartFeed()

            val pass = sync.sync()

            assertNull(pass.failure)
            assertEquals(2, server.listings)
            assertEquals(listOf(TAXI), pass.changes.map { it.expense.uuid })
            assertFalse(pass.changes.single().isFirstListing)
            assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI))
        }
    }

    /** Five expenses, two to a page: every one of them shown, and each page read once. */
    @Test
    fun `a listing on more than one page is read whole`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.pageSize = 2

            val uuids = (1..5).map { "eeeeeeee-eeee-8eee-8eee-00000000000$it" }

            uuids.forEach { uuid ->
                server.put(dinner.copy(uuid = uuid).listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            }

            val pass = syncOf(database).sync()

            assertNull(pass.failure)
            assertEquals(uuids.toSet(), pass.changes.map { it.expense.uuid }.toSet())
            uuids.forEach { assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = it)) }
            assertEquals(1, server.listings)
            assertEquals(2, server.laterPages)
        }
    }

    /** One change to a page: the feed is followed to its end, and every change in it shown. */
    @Test
    fun `changes on more than one page are all read`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            server.pageSize = 1
            server.put(dinner.copy(title = "Dinner").listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.put(taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = firstKey))

            val pass = sync.sync()

            assertNull(pass.failure)
            assertEquals(setOf(DINNER, TAXI), pass.changes.map { it.expense.uuid }.toSet())
            assertEquals("Dinner", assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).expense.title)
            assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI))
            assertEquals(1, server.listings)
        }
    }

    /** As a server putting back the version from before an edit, to have the edit undone on Alice's device. */
    @Test
    fun `a version older than the one shown is never shown again`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val first = dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey)

            server.put(first)
            server.put(dinner.copy(title = "Dinner").listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            server.expenses[DINNER] = first
            server.restartFeed()

            assertTrue(sync.sync().changes.isEmpty())
            assertEquals("Dinner", database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)?.expense?.title)

            // Nor stored at the same revision, as a server serving members different versions under it would.
            assertTrue(
                database.expenseDao.storeGroupExpenses(
                    groupUuid = GROUP,
                    expenses = listOf(OpenedGroupExpense(expense = dinner, revision = 2, authorUuid = BOB).toEntity()),
                ).isEmpty(),
            )
        }
    }

    /**
     * Sealed under the key of an epoch Alice's device has yet to reach: kept as not read yet,
     * opened once the key is held, the server sending nothing again.
     */
    @Test
    fun `a version sealed under a key not held yet is kept until it is`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(
                taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = secondKey, keyGeneration = 2),
            )

            val sync = syncOf(database)

            assertTrue(sync.sync().changes.isEmpty())
            assertEquals(1, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
            assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI))

            // The group sync reads the Commit leading to the second epoch.
            database.groupDao.takeDerivedKeys(
                groupUuid = GROUP,
                keys = listOf(GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)),
            )

            assertEquals(listOf(TAXI), sync.sync().changes.map { it.expense.uuid })
            assertEquals(0, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
            assertEquals(2, server.changeReads)
        }
    }

    /**
     * Carol's version sealed under a key of her own, which the chain leads to for it as well, and
     * Bob's under the one the members derived: whichever is read first, the other is read all the
     * same, each under the key the chain gives for it.
     */
    @Test
    fun `a version sealed under a key of a member's own keeps none other of its generation from being read`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                val carolsKey = generateSymmetricEncryptionKey()

                server.put(
                    taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = carolsKey, keyGeneration = 2),
                )
                server.put(
                    dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = secondKey, keyGeneration = 2),
                )

                val pass = syncOf(
                    database = database,
                    keyOf = { _, _, opens ->
                        listOf(carolsKey, secondKey).firstOrNull(opens)?.let { Result.Success(it) }
                            ?: Result.Error(GroupKeyChainError.Unreadable)
                    },
                ).sync()

                assertNull(pass.failure)
                assertEquals(setOf(DINNER, TAXI), pass.changes.map { it.expense.uuid }.toSet())
            }
        }

    /** Carol's, who is no contact of Alice's, checked against the key the group's tree held for her. */
    @Test
    fun `a version signed by somebody else than the member it names is never shown`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(taxi.listedAs(revision = 1, author = bob, authorUuid = CAROL, groupKey = firstKey))

            val pass = syncOf(database).sync()

            assertTrue(pass.changes.isEmpty())
            assertEquals(mapOf(TAXI to 1L), pass.refused)
            assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI))
            assertEquals(0, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
        }
    }

    /** Recorded by Alice's own device as Carol paid her back, then edited, deleted and restored by her. */
    @Test
    fun `a settlement is deleted by its recipient and nothing else ever changes of it`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val settlement = groupExpenseOf(
                uuid = PAID_BACK,
                groupUuid = GROUP,
                creditorUuid = CAROL,
                amount = "10.00",
                shares = listOf(ALICE to "10.00"),
                title = "",
                description = "",
                isSettlement = true,
            )

            val sync = syncOf(database)

            server.put(settlement.listedAs(revision = 1, author = alice, authorUuid = ALICE, groupKey = firstKey))
            sync.sync()

            // An edit of the amount, which every member's device refuses.
            server.put(settlement.copy(amount = "5.00", beneficiaries = settlement.beneficiaries.map { it.copy(share = "5.00") })
                .listedAs(revision = 2, author = alice, authorUuid = ALICE, groupKey = firstKey))

            val refusedEdit = sync.sync()

            assertTrue(refusedEdit.changes.isEmpty())
            assertEquals(mapOf(PAID_BACK to 2L), refusedEdit.refused)

            server.put(settlement.copy(isDeleted = true).listedAs(revision = 3, author = alice, authorUuid = ALICE, groupKey = firstKey))

            assertEquals(3L, sync.sync().changes.single().revision)

            server.put(settlement.listedAs(revision = 4, author = alice, authorUuid = ALICE, groupKey = firstKey))

            assertTrue(sync.sync().changes.isEmpty())

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK))

            assertTrue(shown.expense.isDeleted)
            assertEquals("10.00", shown.expense.amount)
        }
    }

    /** Nor does a version make an expense a settlement, which moves everyone's balances as a payment. */
    @Test
    fun `an expense never becomes a settlement`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            val asSettlement = taxi.copy(isSettlement = true, beneficiaries = listOf(taxi.beneficiaries.first().copy(share = "20.00")))

            server.put(asSettlement.listedAs(revision = 2, author = alice, authorUuid = ALICE, groupKey = firstKey))

            assertTrue(sync.sync().changes.isEmpty())
            assertFalse(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI)).expense.isSettlement)
        }
    }

    /** The server lists the group's expenses to its current members alone: what the device had stays as it was. */
    @Test
    fun `a group the user left is not synced`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup(isLeft = true)
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val pass = syncOf(database).syncGroup(groupUuid = GROUP)

            assertNull(pass.failure)
            assertEquals(0, server.listings)
        }
    }

    @Test
    fun `a group whose creation is pending is not synced`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            database.groupDao.insertPendingCreation(PendingGroupCreationEntity(groupUuid = GROUP, body = "{}"))

            syncOf(database).sync()

            assertEquals(0, server.listings)
        }
    }

    /**
     * The first pass kept the listing, and could not ask for the links to the key Bob's dinner is
     * sealed under: told by the next one, it is no more news than the rest of the group's expenses.
     */
    @Test
    fun `an expense the first listing could not tell is no news once it is told`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            var areLinksReachable = false

            val sync = syncOf(database) { _, _, _ ->
                if (areLinksReachable) {
                    Result.Success(firstKey)
                } else {
                    Result.Error(GroupKeyChainError.Network(NetworkError.UNREACHABLE))
                }
            }

            val failed = sync.sync()

            assertEquals(GroupExpenseSyncError.Network(NetworkError.UNREACHABLE), failed.failure)
            assertNotNull(database.listedGroupExpenseDao.getFeedToken(groupUuid = GROUP))

            areLinksReachable = true

            assertTrue(sync.sync().changes.single().isFirstListing)
            assertTrue(assertNotNull(database.groupDao.get(uuid = GROUP)).hasTakenInExpenses)
        }
    }

    /**
     * Taken in once, then the feed started over and the listing could not be read again: what Carol
     * wrote meanwhile is news all the same, read by the pass after.
     */
    @Test
    fun `what changed as the listing could not be read again is news`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            var isListingReachable = true

            val sync = syncOf(database, listExpenses = {
                if (isListingReachable) server.getExpenses() else Result.Error(NetworkError.UNREACHABLE)
            })

            sync.sync()

            server.put(taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = firstKey))
            server.restartFeed()
            isListingReachable = false

            assertEquals(GroupExpenseSyncError.Network(NetworkError.UNREACHABLE), sync.sync().failure)
            assertNull(database.listedGroupExpenseDao.getFeedToken(groupUuid = GROUP))

            isListingReachable = true

            assertFalse(sync.sync().changes.single().isFirstListing)
        }
    }

    /** Nothing is stored of a listing that was not read whole, and the next pass reads it whole again. */
    @Test
    fun `a listing that cannot be read is a failure that keeps nothing`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.isReachable = false

            val pass = syncOf(database).sync()

            assertEquals(GroupExpenseSyncError.Network(NetworkError.UNREACHABLE), pass.failure)
            assertNull(database.listedGroupExpenseDao.getFeedToken(groupUuid = GROUP))
        }
    }

    private fun groupOf(isLeft: Boolean) = GroupEntity(
        uuid = GROUP,
        title = "Ski trip",
        description = "",
        currency = GROUP_CURRENCY,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = BOB,
        mlsGroupId = GROUP.encodeToByteArray(),
        groupKey = firstKey,
        keyGeneration = FIRST_KEY_GENERATION,
        isBackedUp = true,
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}
