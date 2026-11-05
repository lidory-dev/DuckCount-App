package group

import database.data.remote.EncryptedFieldDto
import app.lidory.duckcount.ExpenseLimits
import database.AppDatabase
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import mls.InMemoryMlsStore
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.newGroupExpenseId
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

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * What becomes of the expenses Alice writes in a group she is in, see [GroupExpenseWriter],
 * whatever the server answers: it takes them as the API does, unless a test has it lose the answer,
 * and Bob writes too. Her device syncs the group's expenses as it would, see [GroupExpenseSync]. The
 * database is the real one, in memory, and so are the seals and signatures; the server is stood in
 * for.
 */
class GroupExpenseWriterTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())

    private val firstKey = generateSymmetricEncryptionKey()
    private val secondKey = generateSymmetricEncryptionKey()
    private val thirdKey = generateSymmetricEncryptionKey()

    private val server = GroupExpenseServer()

    /** What happens on the server just before each deletion flag Alice's device sends reaches it. */
    private var beforeDeletion: () -> Unit = {}

    /**
     * What the group sync reads when the writer has it run: the key of the epoch a Commit leads to,
     * or nothing, and whether it leaves the group waiting for its owner, see
     * [database.data.local.entity.GroupEntity.isAwaitingOwner].
     */
    private var epochRead: GroupKeyEntity? = null
    private var isLeftAwaitingOwner = false
    private var groupSyncs = 0

    /** Alice paid 90.00 for dinner, half of it each for her and Bob. */
    private val dinner = groupExpenseOf(
        uuid = "",
        groupUuid = GROUP,
        creditorUuid = ALICE,
        amount = "90.00",
        shares = listOf(ALICE to "45.00", BOB to "45.00"),
        title = "  Dinner at the harbour ",
    )

    init {
        alice.pinIdentity(userUuid = BOB, signatureKey = bob.signaturePublicKey())
    }

    /** Alice's sync, which reaches the server's listing and its feed unless [isReachable] says not. */
    private fun syncOf(
        database: AppDatabase,
        userUuid: () -> String? = { ALICE },
        isReachable: Boolean = true,
    ) = GroupExpenseSync(
        userUuid = userUuid,
        client = { Result.Success(alice) },
        listExpenses = { if (isReachable) server.getExpenses() else Result.Error(NetworkError.UNREACHABLE) },
        listExpensesFrom = { _, _ -> error("The server lists every expense on one page") },
        listChanges = { _, since ->
            if (isReachable) server.getChanges(since = since) else Result.Error(NetworkError.UNREACHABLE)
        },
        keyOf = GroupKeyChain(
            keyLinks = { Result.Success(emptyList()) },
            group = database.groupDao::get,
            heldKeys = database.groupDao::getKeys,
            storeRecovered = database.groupDao::insertRecoveredKeys,
        )::keyOf,
        groupDao = database.groupDao,
        expenseDao = database.expenseDao,
        listedDao = database.listedGroupExpenseDao,
    )

    private fun writerOf(database: AppDatabase, sync: GroupExpenseSync = syncOf(database)): GroupExpenseWriter =
        GroupExpenseWriter(
            userUuid = { ALICE },
            group = database.groupDao::get,
            hasPendingCreation = database.groupDao::hasPendingCreation,
            shown = database.expenseDao::getGroupExpense,
            store = database.expenseDao::storeGroupExpenses,
            storeDeletions = database.expenseDao::storeGroupExpenseDeletions,
            client = { Result.Success(alice) },
            create = { _, body -> server.create(body = body) },
            update = { _, expenseUuid, body -> server.update(expenseUuid = expenseUuid, body = body) },
            setDeletion = { _, expenseUuid, body ->
                beforeDeletion()
                server.setDeletion(expenseUuid = expenseUuid, body = body)
            },
            syncGroups = {
                groupSyncs++
                epochRead?.let { database.groupDao.takeDerivedKeys(groupUuid = GROUP, keys = listOf(it)) }
                if (isLeftAwaitingOwner) database.groupDao.setAwaitingOwner(uuid = GROUP, isAwaitingOwner = true)
            },
            syncExpenses = sync::syncGroup,
            newId = { authorUuid ->
                newGroupExpenseId(authorUuid = authorUuid)?.also { server.uuidsByNonce[it.nonce] = it.uuid }
            },
        )

    /**
     * What Bob's device opens of the version the server holds of the expense [uuid] under
     * [groupKey]: that expense of the group at the revision listed, signed by Alice, whose key his
     * device holds, as what the app writes; or null when it does not open so, and every member's
     * device but Alice's refuses it.
     */
    private fun bobOpens(uuid: String, groupKey: ByteArray): OpenedGroupExpense? {
        val listed = assertNotNull(server.expenses[uuid])

        return listed.content
            .unsealGroupExpense(groupKey = groupKey, groupUuid = GROUP, expenseUuid = uuid, revision = listed.revision)
            ?.takeIf { it.isSignedBy(authorKey = alice.signaturePublicKey()) }
            ?.opened(currency = GROUP_CURRENCY)
    }

    /**
     * Whether Bob's device opens the deletion flag the server holds of the expense [uuid] under
     * [groupKey] as Alice's, against the version Alice's device shows, and what it says; null when it
     * does not open so.
     */
    private suspend fun AppDatabase.bobOpensDeletion(uuid: String, groupKey: ByteArray): Boolean? {
        val listed = assertNotNull(assertNotNull(server.expenses[uuid]).deletion)
        val shown = assertNotNull(expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

        return listed.content
            .openGroupExpenseDeletion(groupKey = groupKey, groupUuid = GROUP, expenseUuid = uuid, deletionRevision = listed.deletionRevision)
            ?.takeIf { it.isSignedBy(authorKey = alice.signaturePublicKey(), shown = shown) }
            ?.deletion
            ?.isDeleted
    }

    /** Alice's dinner as the server holds it once created, read at [revision], with its UUID [uuid]. */
    private fun createdAs(uuid: String, revision: Long? = 1) = dinner.copy(
        uuid = uuid,
        title = "Dinner at the harbour",
        beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
        revision = revision,
    )

    /** Has Alice's device edit the expense [uuid] until it stands at its last revision, each edit read from the one before. */
    private suspend fun GroupExpenseWriter.editedToTheLast(uuid: String) {
        (FIRST_GROUP_EXPENSE_REVISION until LAST_GROUP_EXPENSE_REVISION).forEach { revision ->
            assertEquals(
                Result.Success(Unit),
                edit(groupUuid = GROUP, expense = createdAs(uuid = uuid, revision = revision).copy(title = "Dinner $revision")),
            )
        }
    }

    private suspend fun AppDatabase.holdingTheGroup() {
        groupDao.upsertWithMembers(
            group = groupOf(),
            members = emptyList(),
            keys = listOf(groupOf().currentKey()),
            signatureKeys = emptyList(),
        )
    }

    @Test
    fun `an expense is created on the server and shown as it was taken`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            assertEquals(Result.Success(Unit), writerOf(database).create(groupUuid = GROUP, expense = dinner))

            val uuid = server.expenses.keys.single()
            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("Dinner at the harbour", shown.expense.title)
            assertEquals(FIRST_GROUP_EXPENSE_REVISION, shown.expense.revision)
            assertEquals(ALICE, shown.expense.authorUuid)
            assertEquals(FIRST_KEY_GENERATION, server.creates.single().keyGeneration)
        }
    }

    /** What [content] says of its amounts, sealed as the first or a later version of [uuid]. */
    private fun sealedAmounts(content: EncryptedFieldDto, uuid: String, revision: Long): List<String> {
        val version = assertNotNull(
            content.unsealGroupExpense(groupKey = firstKey, groupUuid = GROUP, expenseUuid = uuid, revision = revision),
        ).version

        return listOf(version.amount) + version.beneficiaries.map { it.share }
    }

    @Test
    fun `a create's amounts reach the server and the list written the one way`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val asGiven = dinner.copy(amount = "90", beneficiaries = dinner.beneficiaries.map { it.copy(share = "45") })

            assertEquals(Result.Success(Unit), writerOf(database).create(groupUuid = GROUP, expense = asGiven))

            val uuid = server.expenses.keys.single()

            assertEquals(
                listOf("90.00", "45.00", "45.00"),
                sealedAmounts(content = server.creates.single().content, uuid = uuid, revision = FIRST_GROUP_EXPENSE_REVISION),
            )

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("90.00", shown.expense.amount)
            assertEquals(setOf("45.00"), shown.beneficiaries.mapTo(HashSet()) { it.share })
        }
    }

    @Test
    fun `an edit's amounts reach the server written the one way`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            val edit = dinner.copy(
                uuid = uuid,
                amount = "80.5",
                beneficiaries = dinner.beneficiaries.map { beneficiary ->
                    beneficiary.copy(expenseUuid = uuid, share = if (beneficiary.userUuid == ALICE) "40" else "40.5")
                },
                revision = 1,
            )

            assertEquals(Result.Success(Unit), writer.edit(groupUuid = GROUP, expense = edit))

            assertEquals(
                listOf("80.50", "40.00", "40.50"),
                sealedAmounts(content = server.updates.single().content, uuid = uuid, revision = 2),
            )
        }
    }

    @Test
    fun `a settlement's amounts reach the server written the one way`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            assertEquals(
                Result.Success(Unit),
                writerOf(database).recordSettlement(groupUuid = GROUP, payerUuid = BOB, amount = "45", timestamp = "2026-10-30T21:00:00"),
            )

            assertEquals(
                listOf("45.00", "45.00"),
                sealedAmounts(
                    content = server.creates.single().content,
                    uuid = server.expenses.keys.single(),
                    revision = FIRST_GROUP_EXPENSE_REVISION,
                ),
            )
        }
    }

    /** It would never open on any member's device, a form never letting one through. */
    @Test
    fun `an amount the library cannot read is not sent`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            assertEquals(
                Result.Error(GroupExpenseWriteError.Crypto),
                writerOf(database).create(groupUuid = GROUP, expense = dinner.copy(amount = "ninety")),
            )

            assertTrue(server.creates.isEmpty())
        }
    }

    /**
     * Sealed under the key of an epoch the group left, a Commit Alice's device had yet to read: the
     * group sync reads it, and the create goes again, once, under the key it derives, with the same
     * nonce.
     */
    @Test
    fun `a create sealed under a replaced key is sealed again under the new one and sent once more`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()
                server.keyGeneration = 2
                epochRead = GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)

                assertEquals(Result.Success(Unit), writerOf(database).create(groupUuid = GROUP, expense = dinner))

                assertEquals(listOf(1L, 2L), server.creates.map { it.keyGeneration })
                assertEquals(1, server.creates.map { it.nonce }.distinct().size)
                assertEquals(1, groupSyncs)

                val uuid = server.expenses.keys.single()

                assertEquals("Dinner at the harbour", assertNotNull(bobOpens(uuid = uuid, groupKey = secondKey)).expense.title)
                assertNull(bobOpens(uuid = uuid, groupKey = firstKey))
            }
        }

    /** As for a create: sealed again under the key the group sync derives, at the same revision. */
    @Test
    fun `an edit sealed under a replaced key is sealed again under the new one and sent once more`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                val writer = writerOf(database)

                writer.create(groupUuid = GROUP, expense = dinner)

                val uuid = server.expenses.keys.single()

                server.keyGeneration = 2
                epochRead = GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)

                assertEquals(
                    Result.Success(Unit),
                    writer.edit(groupUuid = GROUP, expense = dinner.copy(uuid = uuid, title = "Dinner", revision = 1)),
                )

                assertEquals(listOf(1L, 2L), server.updates.map { it.keyGeneration })
                assertEquals(listOf(2L, 2L), server.updates.map { it.revision })
                assertEquals("Dinner", assertNotNull(bobOpens(uuid = uuid, groupKey = secondKey)).expense.title)
                assertNull(bobOpens(uuid = uuid, groupKey = firstKey))
            }
        }

    @Test
    fun `a create the group sync brings no newer key for is not sent again`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.keyGeneration = 2

            assertEquals(
                Result.Error(GroupExpenseWriteError.KeyNotCurrent),
                writerOf(database).create(groupUuid = GROUP, expense = dinner),
            )

            assertEquals(1, server.creates.size)
        }
    }

    /**
     * Alice's device refused a Commit every member's device refused, and stays in the epoch before
     * while the server is past it: nothing it seals is taken until the group's creator replaces
     * that Commit, which the writer says.
     */
    @Test
    fun `a write in a group waiting for its owner fails as such`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.keyGeneration = 2
            isLeftAwaitingOwner = true

            assertEquals(
                Result.Error(GroupExpenseWriteError.AwaitingOwner),
                writerOf(database).create(groupUuid = GROUP, expense = dinner),
            )

            assertEquals(1, server.creates.size)
            assertEquals(1, groupSyncs)
            assertTrue(server.expenses.isEmpty())
        }
    }

    /** An edit as much as a create: no version is taken under a key of an epoch the server left. */
    @Test
    fun `an edit in a group waiting for its owner fails as such`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            server.keyGeneration = 2
            isLeftAwaitingOwner = true

            assertEquals(
                Result.Error(GroupExpenseWriteError.AwaitingOwner),
                writer.edit(groupUuid = GROUP, expense = dinner.copy(uuid = uuid, title = "Dinner", revision = 1)),
            )

            assertEquals(listOf(1L), server.updates.map { it.keyGeneration })
        }
    }

    /** Two Commits Alice's device was behind on, the group sync having read only the first: once only. */
    @Test
    fun `a create is sealed again once at most`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.keyGeneration = 3
            epochRead = GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)

            assertEquals(
                Result.Error(GroupExpenseWriteError.KeyNotCurrent),
                writerOf(database).create(groupUuid = GROUP, expense = dinner),
            )

            assertEquals(listOf(1L, 2L), server.creates.map { it.keyGeneration })
            assertEquals(1, groupSyncs)
        }
    }

    /**
     * An edit the server took, its answer lost, saved again from the form that still showed it: the
     * server refuses its revision, holding it already, and the sync shows it is Alice's own.
     */
    @Test
    fun `an edit refused for a revision the server holds as sent is made`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()
            val edit = dinner.copy(uuid = uuid, title = "Dinner", revision = 1)

            server.losesNextAnswer = true

            assertTrue(writer.edit(groupUuid = GROUP, expense = edit) is Result.Error)

            assertEquals(Result.Success(Unit), writer.edit(groupUuid = GROUP, expense = edit))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("Dinner", shown.expense.title)
            assertEquals(2L, shown.expense.revision)
            assertEquals(listOf(2L, 2L), server.updates.map { it.revision })
        }
    }

    /** Bob's edit came first: Alice's is refused, and his shown. */
    @Test
    fun `an edit refused for another member's version is a conflict that shows it`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()
            val created = dinner.copy(
                uuid = uuid,
                title = "Dinner at the harbour",
                beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
                revision = 1,
            )

            server.put(created.copy(title = "Bob's dinner").listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))

            assertEquals(
                Result.Error(GroupExpenseWriteError.Conflict),
                writer.edit(groupUuid = GROUP, expense = created.copy(title = "Alice's dinner")),
            )

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("Bob's dinner", shown.expense.title)
            assertEquals(BOB, shown.expense.authorUuid)
        }
    }

    /**
     * The device locked as Alice's edit waited for its answer: the sync the refusal sets off goes by
     * her UUID as the writer read it before sending, which a locked device cannot read again.
     */
    @Test
    fun `an edit refused for another member's version is a conflict on a device locked since it was sent`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                writerOf(database).create(groupUuid = GROUP, expense = dinner)

                val uuid = server.expenses.keys.single()
                val created = dinner.copy(
                    uuid = uuid,
                    title = "Dinner at the harbour",
                    beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
                    revision = 1,
                )

                server.put(created.copy(title = "Bob's dinner").listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))

                val locked = writerOf(
                    database = database,
                    sync = syncOf(database = database, userUuid = { error("Cannot access Keystore key - device is locked") }),
                )

                assertEquals(
                    Result.Error(GroupExpenseWriteError.Conflict),
                    locked.edit(groupUuid = GROUP, expense = created.copy(title = "Alice's dinner")),
                )

                val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

                assertEquals("Bob's dinner", shown.expense.title)
            }
        }

    /** Edited at the same revision Alice built hers on, saying the same: still Bob's, and so a conflict. */
    @Test
    fun `an edit refused for another member's version saying the same is a conflict`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()
            val edit = dinner.copy(
                uuid = uuid,
                title = "Dinner",
                beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
                revision = 1,
            )

            server.put(edit.listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))

            assertEquals(Result.Error(GroupExpenseWriteError.Conflict), writer.edit(groupUuid = GROUP, expense = edit))
        }
    }

    /**
     * Bob's edit reached Alice's device, a push syncing it, as her form was open on the version
     * before: saved, hers would undo his unseen.
     */
    @Test
    fun `an edit of a version replaced as the form was open is a conflict`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()
            val read = dinner.copy(
                uuid = uuid,
                title = "Dinner at the harbour",
                beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
                revision = 1,
            )

            server.put(read.copy(amount = "60.00", beneficiaries = read.beneficiaries.map { it.copy(share = "30.00") })
                .listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey))
            syncOf(database).syncGroup(groupUuid = GROUP)

            assertEquals(
                Result.Error(GroupExpenseWriteError.Conflict),
                writer.edit(groupUuid = GROUP, expense = read.copy(title = "Alice's dinner")),
            )

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("60.00", shown.expense.amount)
            assertEquals(2L, assertNotNull(server.expenses[uuid]).revision)
        }
    }

    /** Bob deleted it first: deleted as Alice asked, which is all she asked, and shown as Bob's deletion. */
    @Test
    fun `a deletion another member made first is made`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            server.putDeletion(
                expenseUuid = uuid,
                deletion = createdAs(uuid = uuid).flaggedAs(deletionRevision = 1, isDeleted = true, author = bob, authorUuid = BOB, groupKey = firstKey),
            )

            assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertTrue(shown.expense.isDeleted)
            assertEquals(BOB, assertNotNull(shown.deletion).authorUuid)
            assertEquals(ALICE, shown.expense.authorUuid)
            assertEquals(listOf(1L), server.deletions.map { it.deletionRevision })
        }
    }

    /**
     * Bob stored a version that opens as no expense of the group, which every member's device
     * refuses and goes on showing the one before of: Alice's edit is made after it, once.
     */
    @Test
    fun `a write over a version every device refuses is made after it`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()
            val read = dinner.copy(
                uuid = uuid,
                title = "Dinner at the harbour",
                beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
                revision = 1,
            )

            // Sealed as another expense's, and listed as this one's.
            val elsewhere = "ffffffff-ffff-8fff-8fff-ffffffffffff"

            server.put(
                read.copy(uuid = elsewhere).listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey)
                    .copy(uuid = uuid),
            )

            assertEquals(Result.Success(Unit), writer.edit(groupUuid = GROUP, expense = read.copy(title = "Dinner")))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("Dinner", shown.expense.title)
            assertEquals(3L, shown.expense.revision)
            assertEquals(listOf(2L, 3L), server.updates.map { it.revision })
            assertEquals("Dinner", assertNotNull(bobOpens(uuid = uuid, groupKey = firstKey)).expense.title)
        }
    }

    /**
     * Alice's edit written over the version every device refuses, its answer lost, saved again from
     * the form still open on the version before: the server holds it, one revision past the one the
     * form sends, and it is hers.
     */
    @Test
    fun `a write over a refused version saved again once its answer is lost is made`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()
            val read = dinner.copy(
                uuid = uuid,
                title = "Dinner at the harbour",
                beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
                revision = 1,
            )

            // Sealed as another expense's, and listed as this one's.
            val elsewhere = "ffffffff-ffff-8fff-8fff-ffffffffffff"

            server.put(
                read.copy(uuid = elsewhere).listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey)
                    .copy(uuid = uuid),
            )
            server.losesNextAnswer = true

            assertTrue(writer.edit(groupUuid = GROUP, expense = read.copy(title = "Dinner")) is Result.Error)

            assertEquals(Result.Success(Unit), writer.edit(groupUuid = GROUP, expense = read.copy(title = "Dinner")))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("Dinner", shown.expense.title)
            assertEquals(3L, shown.expense.revision)
            assertEquals(listOf(2L, 3L, 2L), server.updates.map { it.revision })
        }
    }

    /**
     * As above, the sync the second save sets off not reaching the server: whether the version it
     * holds one revision past the one the form sends is Alice's cannot be told, and the save fails
     * as the sync did, rather than as another member's change.
     */
    @Test
    fun `a write over a refused version saved again as the sync cannot reach the server fails as the sync did`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                val writer = writerOf(database)

                writer.create(groupUuid = GROUP, expense = dinner)

                val uuid = server.expenses.keys.single()
                val read = dinner.copy(
                    uuid = uuid,
                    title = "Dinner at the harbour",
                    beneficiaries = dinner.beneficiaries.map { it.copy(expenseUuid = uuid) },
                    revision = 1,
                )

                // Sealed as another expense's, and listed as this one's.
                val elsewhere = "ffffffff-ffff-8fff-8fff-ffffffffffff"

                server.put(
                    read.copy(uuid = elsewhere).listedAs(revision = 2, author = bob, authorUuid = BOB, groupKey = firstKey)
                        .copy(uuid = uuid),
                )
                server.losesNextAnswer = true

                assertTrue(writer.edit(groupUuid = GROUP, expense = read.copy(title = "Dinner")) is Result.Error)

                val cutOff = writerOf(database = database, sync = syncOf(database = database, isReachable = false))

                assertEquals(
                    Result.Error(GroupExpenseWriteError.Network(NetworkError.UNREACHABLE)),
                    cutOff.edit(groupUuid = GROUP, expense = read.copy(title = "Dinner")),
                )
                assertEquals(listOf(2L, 3L, 2L), server.updates.map { it.revision })
            }
        }

    /** The server stored it and the answer never came back: sent again, it is found stored, and made once. */
    @Test
    fun `a create whose answer is lost is sent again with its nonce and made once`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.losesNextAnswer = true

            assertEquals(Result.Success(Unit), writerOf(database).create(groupUuid = GROUP, expense = dinner))

            val uuid = server.expenses.keys.single()

            assertEquals(2, server.creates.size)
            assertEquals(1, server.creates.map { it.nonce }.distinct().size)
            assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))
        }
    }

    /** Nothing reached the server: sent again once, and the failure it is. */
    @Test
    fun `a create the server cannot be reached for is sent again once and fails`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            server.isReachable = false

            assertEquals(
                Result.Error(GroupExpenseWriteError.Network(NetworkError.UNREACHABLE)),
                writerOf(database).create(groupUuid = GROUP, expense = dinner),
            )

            assertEquals(2, server.creates.size)
        }
    }

    /** Neither is an edit: the expense's versions are left as they are, its flag counting apart. */
    @Test
    fun `a deletion and a restore are flags the server takes`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))
            assertTrue(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid)).expense.isDeleted)
            assertEquals(true, database.bobOpensDeletion(uuid = uuid, groupKey = firstKey))

            // Deleted already: nothing to send.
            assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))

            assertEquals(Result.Success(Unit), writer.restore(groupUuid = GROUP, expenseUuid = uuid))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertFalse(shown.expense.isDeleted)
            assertEquals(2L, assertNotNull(shown.deletion).deletionRevision)
            assertEquals(FIRST_GROUP_EXPENSE_REVISION, shown.expense.revision)
            assertEquals(listOf(1L, 2L), server.deletions.map { it.deletionRevision })
            assertTrue(server.updates.isEmpty())
            assertEquals(false, database.bobOpensDeletion(uuid = uuid, groupKey = firstKey))
        }
    }

    /** As for a version: sealed again under the key the group sync derives, at the same revision. */
    @Test
    fun `a deletion sealed under a replaced key is sealed again under the new one and sent once more`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                val writer = writerOf(database)

                writer.create(groupUuid = GROUP, expense = dinner)

                val uuid = server.expenses.keys.single()

                server.keyGeneration = 2
                epochRead = GroupKeyEntity(groupUuid = GROUP, generation = 2, groupKey = secondKey)

                assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))

                assertEquals(listOf(1L, 2L), server.deletions.map { it.keyGeneration })
                assertEquals(listOf(1L, 1L), server.deletions.map { it.deletionRevision })
                assertEquals(true, database.bobOpensDeletion(uuid = uuid, groupKey = secondKey))
            }
        }

    /**
     * The server took Alice's deletion and its answer never came back: tapped again, the flag the
     * server holds already is hers, which the sync shows, and nothing more is written.
     */
    @Test
    fun `a deletion whose answer is lost is made once sent again`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            server.losesNextAnswer = true

            assertEquals(
                Result.Error(GroupExpenseWriteError.Network(NetworkError.REQUEST_TIMEOUT)),
                writer.delete(groupUuid = GROUP, expenseUuid = uuid),
            )
            assertFalse(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid)).expense.isDeleted)

            assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertTrue(shown.expense.isDeleted)
            assertEquals(ALICE, assertNotNull(shown.deletion).authorUuid)
            assertEquals(listOf(1L, 1L), server.deletions.map { it.deletionRevision })
        }
    }

    /**
     * Bob stored a flag that opens as no expense's of the group, which every member's device refuses
     * and goes on showing the expense as it was of: Alice's deletion is made after it, once.
     */
    @Test
    fun `a deletion over a flag every device refuses is made after it`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            // Sealed as another expense's flag, and listed as this one's.
            val elsewhere = "ffffffff-ffff-8fff-8fff-ffffffffffff"

            server.putDeletion(
                expenseUuid = uuid,
                deletion = createdAs(uuid = elsewhere).flaggedAs(deletionRevision = 1, isDeleted = true, author = bob, authorUuid = BOB, groupKey = firstKey),
            )

            assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertTrue(shown.expense.isDeleted)
            assertEquals(2L, assertNotNull(shown.deletion).deletionRevision)
            assertEquals(listOf(1L, 2L), server.deletions.map { it.deletionRevision })
            assertEquals(true, database.bobOpensDeletion(uuid = uuid, groupKey = firstKey))
        }
    }

    /**
     * Bob deleted the dinner and restored it, neither synced by Alice's device: the sync her
     * refused deletion sets off shows his restore, the dinner as she saw it when she asked, and
     * her deletion is made after it, once.
     */
    @Test
    fun `a deletion over another member's restore the sync shows is made after it`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            listOf(true, false).forEachIndexed { index, isDeleted ->
                server.putDeletion(
                    expenseUuid = uuid,
                    deletion = createdAs(uuid = uuid).flaggedAs(
                        deletionRevision = index + 1L,
                        isDeleted = isDeleted,
                        author = bob,
                        authorUuid = BOB,
                        groupKey = firstKey,
                    ),
                )
            }

            assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertTrue(shown.expense.isDeleted)
            assertEquals(3L, assertNotNull(shown.deletion).deletionRevision)
            assertEquals(ALICE, assertNotNull(shown.deletion).authorUuid)
            assertEquals(listOf(1L, 3L), server.deletions.map { it.deletionRevision })
            assertEquals(true, database.bobOpensDeletion(uuid = uuid, groupKey = firstKey))
        }
    }

    /** Written over once, then over again by a flag that came meanwhile: what Alice asked is not what shows. */
    @Test
    fun `a deletion refused again once written over is a conflict`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()
            val elsewhere = createdAs(uuid = "ffffffff-ffff-8fff-8fff-ffffffffffff")

            // Before each of Alice's flags, one every member's device refuses, at the revision she sends.
            beforeDeletion = {
                val next = (assertNotNull(server.expenses[uuid]).deletion?.deletionRevision ?: 0L) + 1

                server.putDeletion(
                    expenseUuid = uuid,
                    deletion = elsewhere.flaggedAs(deletionRevision = next, isDeleted = true, author = bob, authorUuid = BOB, groupKey = firstKey),
                )
            }

            assertEquals(Result.Error(GroupExpenseWriteError.Conflict), writer.delete(groupUuid = GROUP, expenseUuid = uuid))

            assertFalse(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid)).expense.isDeleted)
            assertEquals(listOf(1L, 2L), server.deletions.map { it.deletionRevision })
        }
    }

    /** Ten edits, every one it takes: the eleventh is not sent, every member's device refusing a version past the last. */
    @Test
    fun `an edit of an expense at its last revision is refused without being sent`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            writer.editedToTheLast(uuid = uuid)

            assertEquals(
                Result.Error(GroupExpenseWriteError.EditLimitReached),
                writer.edit(groupUuid = GROUP, expense = createdAs(uuid = uuid, revision = LAST_GROUP_EXPENSE_REVISION)),
            )

            assertEquals(LAST_GROUP_EXPENSE_REVISION, assertNotNull(server.expenses[uuid]).revision)
            assertEquals(ExpenseLimits.MAX_GROUP_EXPENSE_EDITS, server.updates.size)
        }
    }

    /** Bob made the last edits as Alice's device was behind: the server refuses hers for good. */
    @Test
    fun `an edit the server refuses at the last revision is refused for good`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            ((FIRST_GROUP_EXPENSE_REVISION + 1)..LAST_GROUP_EXPENSE_REVISION).forEach { revision ->
                server.put(
                    createdAs(uuid = uuid).copy(title = "Bob's dinner $revision")
                        .listedAs(revision = revision, author = bob, authorUuid = BOB, groupKey = firstKey),
                )
            }

            assertEquals(
                Result.Error(GroupExpenseWriteError.EditLimitReached),
                writer.edit(groupUuid = GROUP, expense = createdAs(uuid = uuid).copy(title = "Dinner")),
            )

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals(LAST_GROUP_EXPENSE_REVISION, shown.expense.revision)
            assertEquals(BOB, shown.expense.authorUuid)
        }
    }

    /**
     * Alice's last edit was taken and its answer lost, and she saves it again from the form still
     * open on the version before: the server answers its limit ahead of the revision, and the sync
     * shows the version it holds is hers.
     */
    @Test
    fun `the last edit whose answer is lost is made once sent again`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            (FIRST_GROUP_EXPENSE_REVISION until LAST_GROUP_EXPENSE_REVISION - 1).forEach { revision ->
                writer.edit(groupUuid = GROUP, expense = createdAs(uuid = uuid, revision = revision).copy(title = "Dinner $revision"))
            }

            val last = createdAs(uuid = uuid, revision = LAST_GROUP_EXPENSE_REVISION - 1).copy(title = "The last dinner")

            server.losesNextAnswer = true

            assertTrue(writer.edit(groupUuid = GROUP, expense = last) is Result.Error)

            assertEquals(Result.Success(Unit), writer.edit(groupUuid = GROUP, expense = last))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertEquals("The last dinner", shown.expense.title)
            assertEquals(LAST_GROUP_EXPENSE_REVISION, shown.expense.revision)
            assertEquals(listOf(LAST_GROUP_EXPENSE_REVISION, LAST_GROUP_EXPENSE_REVISION), server.updates.takeLast(2).map { it.revision })
        }
    }

    /** Deleting and restoring are no edit, and use up none of them. */
    @Test
    fun `an expense at its last revision is still deleted and restored`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val writer = writerOf(database)

            writer.create(groupUuid = GROUP, expense = dinner)

            val uuid = server.expenses.keys.single()

            writer.editedToTheLast(uuid = uuid)

            assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))
            assertEquals(Result.Success(Unit), writer.restore(groupUuid = GROUP, expenseUuid = uuid))

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

            assertFalse(shown.expense.isDeleted)
            assertEquals(LAST_GROUP_EXPENSE_REVISION, shown.expense.revision)
            assertEquals(listOf(1L, 2L), server.deletions.map { it.deletionRevision })
        }
    }

    /** Bob paid Alice back: she records it, and may delete it, but nobody edits it or restores it. */
    @Test
    fun `a settlement is recorded and deleted by its recipient alone and never edited or restored`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.holdingTheGroup()

                val writer = writerOf(database)

                assertEquals(
                    Result.Success(Unit),
                    writer.recordSettlement(groupUuid = GROUP, payerUuid = BOB, amount = "45.00", timestamp = "2026-10-30T21:00:00"),
                )

                val uuid = server.expenses.keys.single()
                val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = uuid))

                assertTrue(shown.expense.isSettlement)
                assertEquals(BOB, shown.expense.creditorUuid)
                assertEquals(listOf(ALICE), shown.beneficiaries.map { it.userUuid })

                val edit = dinner.copy(uuid = uuid, amount = "40.00", revision = 1)

                assertEquals(Result.Error(GroupExpenseWriteError.SettlementLocked), writer.edit(groupUuid = GROUP, expense = edit))

                assertEquals(Result.Success(Unit), writer.delete(groupUuid = GROUP, expenseUuid = uuid))
                assertEquals(true, database.bobOpensDeletion(uuid = uuid, groupKey = firstKey))

                assertEquals(
                    Result.Error(GroupExpenseWriteError.SettlementLocked),
                    writer.restore(groupUuid = GROUP, expenseUuid = uuid),
                )

                assertTrue(server.updates.isEmpty())
                assertEquals(listOf(1L), server.deletions.map { it.deletionRevision })
            }
        }

    /** Alice paid Bob back, which only Bob records, and deletes. */
    @Test
    fun `a settlement whose recipient is somebody else is neither recorded nor deleted`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()

            val toBob = groupExpenseOf(
                uuid = "",
                groupUuid = GROUP,
                creditorUuid = ALICE,
                amount = "45.00",
                shares = listOf(BOB to "45.00"),
                title = "",
                description = "",
                isSettlement = true,
            )

            val writer = writerOf(database)

            assertEquals(Result.Error(GroupExpenseWriteError.SettlementLocked), writer.create(groupUuid = GROUP, expense = toBob))

            val uuid = "cccccccc-cccc-8ccc-8ccc-cccccccccccc"
            val recorded = toBob.copy(uuid = uuid, beneficiaries = toBob.beneficiaries.map { it.copy(expenseUuid = uuid) })

            server.put(recorded.listedAs(revision = 1, author = bob, authorUuid = BOB, groupKey = firstKey))
            GroupExpenseSync(
                userUuid = { ALICE },
                client = { Result.Success(alice) },
                listExpenses = { server.getExpenses() },
                listExpensesFrom = { _, _ -> error("One page") },
                listChanges = { _, since -> server.getChanges(since = since) },
                keyOf = { _, _, _ -> Result.Success(firstKey) },
                groupDao = database.groupDao,
                expenseDao = database.expenseDao,
                listedDao = database.listedGroupExpenseDao,
            ).sync()

            assertEquals(Result.Error(GroupExpenseWriteError.SettlementLocked), writer.delete(groupUuid = GROUP, expenseUuid = uuid))
            assertTrue(server.creates.isEmpty())
            assertTrue(server.deletions.isEmpty())
        }
    }

    /** Nothing is written in a group the server may refuse to create, which would leave it behind. */
    @Test
    fun `nothing is written in a group whose creation is pending`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            database.groupDao.insertPendingCreation(PendingGroupCreationEntity(groupUuid = GROUP, body = "{}"))

            assertEquals(
                Result.Error(GroupExpenseWriteError.CreationPending),
                writerOf(database).create(groupUuid = GROUP, expense = dinner),
            )

            assertTrue(server.creates.isEmpty())
        }
    }

    @Test
    fun `nothing is written in a group the user left`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.holdingTheGroup()
            database.groupDao.markLeft(uuid = GROUP)

            assertEquals(
                Result.Error(GroupExpenseWriteError.NotMember),
                writerOf(database).create(groupUuid = GROUP, expense = dinner),
            )
        }
    }

    private fun groupOf() = GroupEntity(
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
        isLeft = false,
        handedProfileKeyHash = null,
    )
}
