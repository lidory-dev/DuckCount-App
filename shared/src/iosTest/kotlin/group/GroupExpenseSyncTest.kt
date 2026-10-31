package group

import database.AppDatabase
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.withInMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
private const val DAVE = "44444444-4444-4444-4444-444444444444"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

private const val DINNER = "eeeeeeee-eeee-8eee-8eee-eeeeeeeeeeee"
private const val TAXI = "dddddddd-dddd-8ddd-8ddd-dddddddddddd"
private const val PAID_BACK = "cccccccc-cccc-8ccc-8ccc-cccccccccccc"
private const val PAID_DAVE_BACK = "ffffffff-ffff-8fff-8fff-ffffffffffff"

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
    private val dave = MlsClient(userUuid = DAVE, secret = ByteArray(32) { 4 }, store = InMemoryMlsStore())

    private val firstKey = generateSymmetricEncryptionKey()
    private val secondKey = generateSymmetricEncryptionKey()

    private val server = GroupExpenseServer()

    /** The links back through the group's keys the server lists. */
    private val links = mutableListOf<GroupKeyLinkDto>()

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
            keyLinks = { Result.Success(links.toList()) },
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

    /**
     * Alice's device holding the group at [groupKey], of [keyGeneration] and no older one, its first
     * unless a test says otherwise, Carol's leaf read in its tree.
     */
    private suspend fun AppDatabase.holdingTheGroup(
        isLeft: Boolean = false,
        groupKey: ByteArray = firstKey,
        keyGeneration: Long = FIRST_KEY_GENERATION,
    ) {
        val group = groupOf(isLeft = isLeft, groupKey = groupKey, keyGeneration = keyGeneration)

        groupDao.upsertWithMembers(
            group = group,
            members = emptyList(),
            keys = listOf(group.currentKey()),
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

    /**
     * Recorded by Alice's own device as Carol paid her back, then edited, deleted by Carol and by
     * Alice, and restored by her: only Alice's deletion is shown.
     */
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

            // An edit of the amount, which every member's device refuses by its revision alone,
            // whether it showed the first version or not.
            server.put(settlement.copy(amount = "5.00", beneficiaries = settlement.beneficiaries.map { it.copy(share = "5.00") })
                .listedAs(revision = 2, author = alice, authorUuid = ALICE, groupKey = firstKey))

            val refusedEdit = sync.sync()

            assertTrue(refusedEdit.changes.isEmpty())
            assertEquals(mapOf(PAID_BACK to 2L), refusedEdit.refused)

            // Carol paid it, and is not who it was paid to.
            server.putDeletion(
                expenseUuid = PAID_BACK,
                deletion = settlement.flaggedAs(deletionRevision = 1, isDeleted = true, author = carol, authorUuid = CAROL, groupKey = firstKey),
            )

            assertTrue(sync.sync().changes.isEmpty())
            assertFalse(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK)).expense.isDeleted)

            server.putDeletion(
                expenseUuid = PAID_BACK,
                deletion = settlement.flaggedAs(deletionRevision = 2, isDeleted = true, author = alice, authorUuid = ALICE, groupKey = firstKey),
            )

            val deleted = sync.sync().changes.single()

            assertTrue(deleted.expense.isDeleted)
            assertEquals(1L, deleted.revision)
            assertEquals(ALICE, deleted.authorUuid)

            server.putDeletion(
                expenseUuid = PAID_BACK,
                deletion = settlement.flaggedAs(deletionRevision = 3, isDeleted = false, author = alice, authorUuid = ALICE, groupKey = firstKey),
            )

            assertTrue(sync.sync().changes.isEmpty())

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK))

            assertTrue(shown.expense.isDeleted)
            assertEquals(2L, assertNotNull(shown.deletion).deletionRevision)
            assertEquals("10.00", shown.expense.amount)
        }
    }

    /**
     * Carol paid Bob back, and Dave too, and each one's second version of it says Alice did
     * instead. Alice's device, brought back after a log-out, shows no row of either to tell an edit
     * by, and holds no key for Dave, who is no contact of hers: it refuses both by their revision
     * alone, Bob's held to his pin and Dave's to nothing, and stores neither.
     */
    @Test
    fun `a settlement past its first revision is never shown to a device that never showed the first`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                fun rewritten(uuid: String, recipientUuid: String) = groupExpenseOf(
                    uuid = uuid,
                    groupUuid = GROUP,
                    creditorUuid = ALICE,
                    amount = "10.00",
                    shares = listOf(recipientUuid to "10.00"),
                    title = "",
                    description = "",
                    isSettlement = true,
                )

                server.put(
                    rewritten(uuid = PAID_BACK, recipientUuid = BOB)
                        .listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey),
                )
                server.put(
                    rewritten(uuid = PAID_DAVE_BACK, recipientUuid = DAVE)
                        .listedAs(revision = 2, author = dave, authorUuid = DAVE, groupKey = firstKey),
                )

                val pass = syncOf(database).sync()

                assertNull(pass.failure)
                assertTrue(pass.changes.isEmpty())
                assertEquals(mapOf(PAID_BACK to 2L, PAID_DAVE_BACK to 2L), pass.refused)
                assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK))
                assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_DAVE_BACK))
            }
        }

    /**
     * Alice's device holds the group's second key alone, as one that joined at the second epoch
     * does, and Bob's second version of what Carol paid him back is sealed under the first: the
     * link back to it gives the key it opens under, and it is refused by its revision, not kept as
     * one sealed under a key not held yet, which would keep the group from ever being taken in.
     */
    @Test
    fun `a settlement past its first revision under a key got back through the links is refused`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup(groupKey = secondKey, keyGeneration = 2)
                links += GroupKeyLinkDto(
                    generation = 2,
                    publisherUuid = BOB,
                    previousKey = assertNotNull(
                        sealKeyLink(previousKey = firstKey, groupKey = secondKey, groupUuid = GROUP, generation = 2),
                    ),
                )

                val rewritten = groupExpenseOf(
                    uuid = PAID_BACK,
                    groupUuid = GROUP,
                    creditorUuid = CAROL,
                    amount = "5.00",
                    shares = listOf(BOB to "5.00"),
                    title = "",
                    description = "",
                    isSettlement = true,
                )

                server.put(rewritten.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))

                val pass = syncOf(database).sync()

                assertNull(pass.failure)
                assertTrue(pass.changes.isEmpty())
                assertEquals(mapOf(PAID_BACK to 2L), pass.refused)
                assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK))
                assertEquals(0, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
                assertTrue(assertNotNull(database.groupDao.get(uuid = GROUP)).hasTakenInExpenses)
            }
        }

    /** Carol deleted Bob's dinner: told as hers, its version left as Bob wrote it. */
    @Test
    fun `a deletion flag is shown and told as its author's`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(deletionRevision = 1, isDeleted = true, author = carol, authorUuid = CAROL, groupKey = firstKey),
            )

            val change = sync.sync().changes.single()

            assertTrue(change.expense.isDeleted)
            assertEquals(CAROL, change.expense.deletedByUuid)
            assertEquals(1L, change.revision)
            assertEquals(CAROL, change.authorUuid)
            assertFalse(assertNotNull(change.previous).isDeleted)

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER))

            assertTrue(shown.expense.isDeleted)
            assertEquals(BOB, shown.expense.authorUuid)
            assertEquals(CAROL, assertNotNull(shown.deletion).authorUuid)
        }
    }

    /** As a server putting back the flag from before a deletion, to have the expense restored on Alice's device. */
    @Test
    fun `a flag older than the one shown is never shown again`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val restored = dinner.flaggedAs(deletionRevision = 2, isDeleted = false, author = bob, authorUuid = BOB, groupKey = firstKey)

            server.putDeletion(expenseUuid = DINNER, deletion = restored)
            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(deletionRevision = 3, isDeleted = true, author = bob, authorUuid = BOB, groupKey = firstKey),
            )

            val sync = syncOf(database)

            sync.sync()

            assertTrue(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).expense.isDeleted)

            server.putDeletion(expenseUuid = DINNER, deletion = restored)
            server.restartFeed()

            assertTrue(sync.sync().changes.isEmpty())
            assertTrue(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).expense.isDeleted)
        }
    }

    /** Created and deleted between two of Alice's syncs: shown deleted from the first, and never news. */
    @Test
    fun `a flag listed with an expense new to the device is applied as its version is stored`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val sync = syncOf(database)

            sync.sync()

            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(deletionRevision = 1, isDeleted = true, author = carol, authorUuid = CAROL, groupKey = firstKey),
            )

            val change = sync.sync().changes.single()

            assertNull(change.previous)
            assertTrue(change.expense.isDeleted)
            assertEquals(BOB, change.authorUuid)
            assertTrue(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).expense.isDeleted)
        }
    }

    /**
     * Carol's taxi, new to Alice's device, listed deleted, as the pass storing it is cut short on its
     * way to the key of a flag of Bob's dinner, which her device shows, as the user leaving the
     * group's screen, or the system stopping the push sync, cuts one short: the taxi was stored
     * deleted from the first, with its flag, never counted in the group's balances, and the next pass
     * tells nothing of it.
     */
    @Test
    fun `an expense new to the device is stored with its flag whatever cuts the pass short`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            syncOf(database).sync()

            server.put(taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = firstKey))
            server.putDeletion(
                expenseUuid = TAXI,
                deletion = taxi.flaggedAs(deletionRevision = 1, isDeleted = true, author = carol, authorUuid = CAROL, groupKey = firstKey),
            )
            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(
                    deletionRevision = 1,
                    isDeleted = true,
                    author = bob,
                    authorUuid = BOB,
                    groupKey = secondKey,
                    keyGeneration = 2,
                ),
            )

            val askedForTheSecondKey = CompletableDeferred<Unit>()

            val pass = launch {
                syncOf(
                    database = database,
                    keyOf = { _, generation, _ ->
                        if (generation == FIRST_KEY_GENERATION) {
                            Result.Success(firstKey)
                        } else {
                            askedForTheSecondKey.complete(Unit)

                            awaitCancellation()
                        }
                    },
                ).sync()
            }

            askedForTheSecondKey.await()
            pass.cancelAndJoin()

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI))

            assertTrue(shown.expense.isDeleted)
            assertEquals(CAROL, assertNotNull(shown.deletion).authorUuid)
            assertEquals(
                listOf(DINNER),
                database.expenseDao.getAllFromGroupWithoutDeletedOnesAsFlow(groupUuid = GROUP).first().map { it.expense.uuid },
            )

            assertTrue(syncOf(database).sync().changes.isEmpty())
        }
    }

    /**
     * Carol's taxi sealed under the key of an epoch Alice's device has yet to reach, and its flag
     * under one it holds: neither shown until the version can be read, which the flag is checked
     * against, and then both.
     */
    @Test
    fun `a flag whose version cannot be read yet is applied once it can`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(
                taxi.listedAs(revision = 1, author = carol, authorUuid = CAROL, groupKey = secondKey, keyGeneration = 2),
            )
            server.putDeletion(
                expenseUuid = TAXI,
                deletion = taxi.flaggedAs(deletionRevision = 1, isDeleted = true, author = carol, authorUuid = CAROL, groupKey = firstKey),
            )

            val sync = syncOf(database)

            assertTrue(sync.sync().changes.isEmpty())
            assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI))

            database.groupDao.takeDerivedKeys(
                groupUuid = GROUP,
                keys = listOf(GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)),
            )

            assertTrue(sync.sync().changes.single().expense.isDeleted)
            assertEquals(CAROL, assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = TAXI)).deletion?.authorUuid)
        }
    }

    /**
     * Bob's dinner, new to Alice's device, its flag sealed under the key of an epoch her device has
     * yet to reach: nothing of it shown, and counted as not read yet, the group not taken in, until
     * the key is held, and then shown deleted from the first.
     */
    @Test
    fun `a flag sealed under a key not held yet keeps an expense new to the device unshown until it is`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()
                server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
                server.putDeletion(
                    expenseUuid = DINNER,
                    deletion = dinner.flaggedAs(
                        deletionRevision = 1,
                        isDeleted = true,
                        author = carol,
                        authorUuid = CAROL,
                        groupKey = secondKey,
                        keyGeneration = 2,
                    ),
                )

                val sync = syncOf(database)
                val unread = sync.sync()

                assertNull(unread.failure)
                assertTrue(unread.changes.isEmpty())
                assertTrue(unread.refused.isEmpty())
                assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER))
                assertEquals(1, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
                assertFalse(assertNotNull(database.groupDao.get(uuid = GROUP)).hasTakenInExpenses)

                database.groupDao.takeDerivedKeys(
                    groupUuid = GROUP,
                    keys = listOf(GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)),
                )

                val change = sync.sync().changes.single()

                assertTrue(change.expense.isDeleted)
                assertNull(change.previous)
                assertEquals(BOB, change.authorUuid)
                assertTrue(change.isFirstListing)
                assertEquals(CAROL, assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).deletion?.authorUuid)
                assertEquals(0, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
                assertTrue(assertNotNull(database.groupDao.get(uuid = GROUP)).hasTakenInExpenses)
            }
        }

    /**
     * Bob's dinner shown, its flag since sealed under the key of an epoch Alice's device has yet to
     * reach: shown as it was until the key is held.
     */
    @Test
    fun `a flag sealed under a key not held yet leaves the expense shown as it was until it is`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(
                    deletionRevision = 1,
                    isDeleted = true,
                    author = carol,
                    authorUuid = CAROL,
                    groupKey = secondKey,
                    keyGeneration = 2,
                ),
            )

            assertTrue(sync.sync().changes.isEmpty())
            assertFalse(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).expense.isDeleted)

            // Shown, so not counted among those the group's balances are short of.
            assertEquals(0, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())

            database.groupDao.takeDerivedKeys(
                groupUuid = GROUP,
                keys = listOf(GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)),
            )

            val change = sync.sync().changes.single()

            assertTrue(change.expense.isDeleted)
            assertEquals(CAROL, change.authorUuid)
            assertFalse(change.isFirstListing)
        }
    }

    /** Bob edited his dinner and Carol deleted it between two of Alice's syncs: one change, Carol's deletion. */
    @Test
    fun `an edit and a deletion in one pass are one change`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            val edited = dinner.copy(title = "Dinner")

            server.put(edited.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.putDeletion(
                expenseUuid = DINNER,
                deletion = edited.flaggedAs(deletionRevision = 1, isDeleted = true, author = carol, authorUuid = CAROL, groupKey = firstKey),
            )

            val change = sync.sync().changes.single()

            assertEquals("Dinner", change.expense.title)
            assertTrue(change.expense.isDeleted)
            assertEquals(2L, change.revision)
            assertEquals(CAROL, change.authorUuid)
            assertEquals("Dinner at the harbour", assertNotNull(change.previous).title)
            assertFalse(assertNotNull(change.previous).isDeleted)
        }
    }

    /** Bob deleted it, and his edit since leaves it deleted: a version is stored as the flag shown says. */
    @Test
    fun `a version stored after a deletion stays deleted`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(deletionRevision = 1, isDeleted = true, author = bob, authorUuid = BOB, groupKey = firstKey),
            )

            val sync = syncOf(database)

            sync.sync()

            server.put(dinner.copy(title = "Dinner").listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))

            val change = sync.sync().changes.single()

            assertTrue(change.expense.isDeleted)
            assertTrue(assertNotNull(change.previous).isDeleted)
            assertEquals("Dinner", assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).expense.title)
        }
    }

    /**
     * Carol's, who is no contact of Alice's, checked against the key the group's tree held for her:
     * Bob signed it. The dinner shown stays as it was.
     */
    @Test
    fun `a flag signed by somebody else than the member it names is never shown`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(deletionRevision = 1, isDeleted = true, author = bob, authorUuid = CAROL, groupKey = firstKey),
            )

            assertTrue(sync.sync().changes.isEmpty())

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER))

            assertFalse(shown.expense.isDeleted)
            assertNull(shown.deletion)
        }
    }

    /**
     * Listed to Alice's device as it first lists the group, after she joined it or logged out: what
     * Bob recorded of Carol paying him back, with Carol's deletion of it, which only Bob may delete,
     * and Bob's dinner, with a flag that does not open under the key of the generation it is listed
     * under. Each version alone would show its expense as though nobody deleted it: nothing of
     * either is shown, and neither version is taken for one refused.
     */
    @Test
    fun `a flag refused of an expense new to the device keeps it unshown`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val settlement = groupExpenseOf(
                uuid = PAID_BACK,
                groupUuid = GROUP,
                creditorUuid = CAROL,
                amount = "10.00",
                shares = listOf(BOB to "10.00"),
                title = "",
                description = "",
                isSettlement = true,
            )

            server.put(settlement.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.putDeletion(
                expenseUuid = PAID_BACK,
                deletion = settlement.flaggedAs(deletionRevision = 1, isDeleted = true, author = carol, authorUuid = CAROL, groupKey = firstKey),
            )
            server.put(dinner.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            server.putDeletion(
                expenseUuid = DINNER,
                deletion = dinner.flaggedAs(deletionRevision = 1, isDeleted = true, author = bob, authorUuid = BOB, groupKey = secondKey),
            )

            val pass = syncOf(database).sync()

            assertNull(pass.failure)
            assertTrue(pass.changes.isEmpty())
            assertTrue(pass.refused.isEmpty())
            assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK))
            assertNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER))
            assertEquals(0, database.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
            assertTrue(assertNotNull(database.groupDao.get(uuid = GROUP)).hasTakenInExpenses)
        }
    }

    /**
     * Bob recorded Carol paying him back and deleted it, and Carol, who paid it, wrote a flag
     * restoring it over his, which every member's device refuses, the server keeping the newest
     * flag alone. Alice's device, which showed Bob's deletion, goes on showing it deleted; once she
     * logs out and brings the group back, it shows nothing of it, rather than the settlement counted
     * again.
     */
    @Test
    fun `a settlement's deletion written over with a refused flag shows on no device as undone`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                val settlement = groupExpenseOf(
                    uuid = PAID_BACK,
                    groupUuid = GROUP,
                    creditorUuid = CAROL,
                    amount = "10.00",
                    shares = listOf(BOB to "10.00"),
                    title = "",
                    description = "",
                    isSettlement = true,
                )

                server.put(settlement.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
                server.putDeletion(
                    expenseUuid = PAID_BACK,
                    deletion = settlement.flaggedAs(deletionRevision = 1, isDeleted = true, author = bob, authorUuid = BOB, groupKey = firstKey),
                )

                val sync = syncOf(database)

                assertTrue(sync.sync().changes.single().expense.isDeleted)

                server.putDeletion(
                    expenseUuid = PAID_BACK,
                    deletion = settlement.flaggedAs(deletionRevision = 2, isDeleted = false, author = carol, authorUuid = CAROL, groupKey = firstKey),
                )

                assertTrue(sync.sync().changes.isEmpty())

                val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK))

                assertTrue(shown.expense.isDeleted)
                assertEquals(1L, assertNotNull(shown.deletion).deletionRevision)

                withInMemoryDatabase { broughtBack ->
                    broughtBack.holdingTheGroup()

                    val pass = syncOf(broughtBack).sync()

                    assertNull(pass.failure)
                    assertTrue(pass.changes.isEmpty())
                    assertTrue(pass.refused.isEmpty())
                    assertNull(broughtBack.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = PAID_BACK))
                    assertEquals(0, broughtBack.listedGroupExpenseDao.countUnreadableAsFlow(groupUuid = GROUP).first())
                }
            }
        }

    /** As a server storing an eleventh edit the API refuses: every member's device refuses it whoever signed it. */
    @Test
    fun `a version past the last revision is never shown`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.put(dinner.listedAs(revision = LAST_GROUP_EXPENSE_REVISION, author = bob, authorUuid = BOB, groupKey = firstKey))

            val sync = syncOf(database)

            sync.sync()

            server.put(
                dinner.copy(title = "Dinner").listedAs(revision = LAST_GROUP_EXPENSE_REVISION + 1, author = bob, authorUuid = BOB, groupKey = firstKey),
            )

            val pass = sync.sync()

            assertTrue(pass.changes.isEmpty())
            assertEquals(mapOf(DINNER to LAST_GROUP_EXPENSE_REVISION + 1), pass.refused)
            assertEquals(
                LAST_GROUP_EXPENSE_REVISION,
                assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = DINNER)).expense.revision,
            )
        }
    }

    /**
     * Nor does a version make an expense a settlement, which moves everyone's balances as a
     * payment: refused by its revision alone, past the first, whoever signed it.
     */
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

    private fun groupOf(isLeft: Boolean, groupKey: ByteArray, keyGeneration: Long) = GroupEntity(
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
        isLeft = isLeft,
        handedProfileKeyHash = null,
    )
}
