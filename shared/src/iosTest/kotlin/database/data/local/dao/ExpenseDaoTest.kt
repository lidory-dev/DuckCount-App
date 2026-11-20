package database.data.local.dao

import database.data.local.entity.BeneficiaryEntity
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.GroupExpenseDeletionEntity
import database.domain.CurrencyCatalog
import database.withInMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

private const val EXPENSE = "eeeeeeee-eeee-8eee-8eee-eeeeeeeeeeee"
private const val OTHER_EXPENSE = "ffffffff-ffff-8fff-8fff-ffffffffffff"

/**
 * What the database keeps of a group expense written over: its beneficiaries replaced whole, and
 * stored over nothing of another kind, nor of another group, see [ExpenseDao.storeGroupExpenses];
 * and of its deletion flags, the newest alone, setting the expense deleted or not as they say, see
 * [ExpenseDao.storeGroupExpenseDeletions], run on the database itself.
 */
class ExpenseDaoTest {

    /** As a member having used the same nonce for a one-time expense and a group's, which derive the same UUID. */
    @Test
    fun `a group expense is not stored over a one-time expense of the same uuid`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.upsertOneTimeExpense(expense = expenseOf(groupUuid = null), revision = 0)

            val stored = database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))

            assertTrue(stored.isEmpty())
            assertNull(assertNotNull(database.expenseDao.getWithBeneficiaries(uuid = EXPENSE)).expense.groupUuid)
        }
    }

    @Test
    fun `a one-time expense is not stored over a group expense of the same uuid`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))

            database.expenseDao.replaceAllOneTimeExpenses(
                expenses = listOf(expenseOf(groupUuid = null) to 5L),
                goneUuids = emptyList(),
            )

            assertEquals(GROUP, assertNotNull(database.expenseDao.getWithBeneficiaries(uuid = EXPENSE)).expense.groupUuid)
        }
    }

    /** The server takes no UUID twice, so of two groups listing one, one listing is made up. */
    @Test
    fun `a group expense is not stored over another group's`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))

            val stored = database.expenseDao.storeGroupExpenses(
                groupUuid = OTHER_GROUP,
                expenses = listOf(expenseOf(groupUuid = OTHER_GROUP, revision = 2)),
            )

            assertTrue(stored.isEmpty())
            assertEquals(GROUP, assertNotNull(database.expenseDao.getWithBeneficiaries(uuid = EXPENSE)).expense.groupUuid)
        }
    }

    @Test
    fun `a group expense stored says what it replaced`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))

            val stored = database.expenseDao.storeGroupExpenses(
                groupUuid = GROUP,
                expenses = listOf(expenseOf(revision = 2, shares = listOf(ALICE to "30.00"))),
            ).single()

            assertEquals(1L, stored.previous?.expense?.revision)
            assertEquals(listOf(ALICE), stored.expense.beneficiaries.map { it.userUuid })
            assertEquals(listOf(ALICE), database.expenseDao.getBeneficiaryUuids(expenseUuid = EXPENSE))
        }
    }

    @Test
    fun `a deletion flag stored sets the expense shown deleted`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))

            val stored = database.expenseDao.storeGroupExpenseDeletions(
                groupUuid = GROUP,
                deletions = listOf(deletionOf(deletionRevision = 1, isDeleted = true)),
            ).single()

            assertFalse(assertNotNull(stored.previous).expense.isDeleted)
            assertTrue(stored.expense.expense.isDeleted)

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = EXPENSE))

            assertTrue(shown.expense.isDeleted)
            assertEquals(1L, shown.expense.revision)
            assertEquals(BOB, assertNotNull(shown.deletion).authorUuid)

            // Read off the expense itself, as what counts the expenses not deleted reads it.
            assertEquals(0, database.expenseDao.count())
        }
    }

    /** As a server putting back the flag from before a deletion, to have the expense restored. */
    @Test
    fun `a deletion flag no newer than the one shown is not stored`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))
            database.expenseDao.storeGroupExpenseDeletions(
                groupUuid = GROUP,
                deletions = listOf(deletionOf(deletionRevision = 2, isDeleted = true)),
            )

            val stored = database.expenseDao.storeGroupExpenseDeletions(
                groupUuid = GROUP,
                deletions = listOf(deletionOf(deletionRevision = 1, isDeleted = false), deletionOf(deletionRevision = 2, isDeleted = false)),
            )

            assertTrue(stored.isEmpty())

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = EXPENSE))

            assertTrue(shown.expense.isDeleted)
            assertEquals(2L, assertNotNull(shown.deletion).deletionRevision)
        }
    }

    /** An edit of a deleted expense: the version says nothing of it, and the flag stored before it does. */
    @Test
    fun `a version stored after a deletion flag is deleted as the flag says`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))
            database.expenseDao.storeGroupExpenseDeletions(
                groupUuid = GROUP,
                deletions = listOf(deletionOf(deletionRevision = 1, isDeleted = true)),
            )

            val stored = database.expenseDao.storeGroupExpenses(
                groupUuid = GROUP,
                expenses = listOf(expenseOf(revision = 2, shares = listOf(ALICE to "30.00"))),
            ).single()

            assertTrue(stored.expense.expense.isDeleted)
            assertEquals(BOB, assertNotNull(stored.expense.deletion).authorUuid)

            val shown = assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = EXPENSE))

            assertTrue(shown.expense.isDeleted)
            assertEquals(2L, shown.expense.revision)
            assertEquals(0, database.expenseDao.count())
        }
    }

    /**
     * No flag is stored without a version of its expense this device shows in that group, which it
     * was checked against: the one shown as the flag was opened, or for an expense new to the
     * device, the one stored with it, see [ExpenseDao.storeGroupExpensesWithDeletions].
     */
    @Test
    fun `a deletion flag of an expense not shown in the group is not stored`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(groupUuid = GROUP, expenses = listOf(expenseOf(revision = 1)))

            val stored = database.expenseDao.storeGroupExpenseDeletions(
                groupUuid = OTHER_GROUP,
                deletions = listOf(deletionOf(deletionRevision = 1, isDeleted = true)),
            ) + database.expenseDao.storeGroupExpenseDeletions(
                groupUuid = GROUP,
                deletions = listOf(GroupExpenseDeletionEntity(expenseUuid = OTHER_EXPENSE, deletionRevision = 1, isDeleted = true, authorUuid = BOB)),
            )

            assertTrue(stored.isEmpty())
            assertNull(database.expenseDao.getGroupExpenseDeletion(expenseUuid = EXPENSE))
            assertNull(database.expenseDao.getGroupExpenseDeletion(expenseUuid = OTHER_EXPENSE))
            assertFalse(assertNotNull(database.expenseDao.getGroupExpense(groupUuid = GROUP, uuid = EXPENSE)).expense.isDeleted)
        }
    }

    /** iOS's list, which asks for more as it scrolls, as Android's pages through them. */
    @Test
    fun `the newest one-time expenses come first and as many as asked for`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            // Two at the same time, which come by UUID, and a group's, which is not one of them.
            listOf(
                expenseAt(uuid = "c", timestamp = "2026-10-28T09:00:00"),
                expenseAt(uuid = "b", timestamp = "2026-10-30T19:45:00"),
                expenseAt(uuid = "a", timestamp = "2026-10-30T19:45:00"),
                expenseAt(uuid = "d", timestamp = "2026-10-29T12:00:00"),
            ).forEach { database.expenseDao.upsertOneTimeExpense(expense = it, revision = 0) }
            database.expenseDao.storeGroupExpenses(
                groupUuid = GROUP,
                expenses = listOf(expenseAt(uuid = "g", timestamp = "2026-10-31T08:00:00", groupUuid = GROUP, revision = 1)),
            )

            assertEquals(listOf("a", "b", "d"), database.expenseDao.getNewestWithoutGroupAsFlow(limit = 3).first().map { it.expense.uuid })
            assertEquals(listOf("a", "b", "d", "c"), database.expenseDao.getNewestWithoutGroupAsFlow(limit = 20).first().map { it.expense.uuid })
        }
    }

    @Test
    fun `a group's newest expenses come first and as many as asked for and its own alone`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.storeGroupExpenses(
                groupUuid = GROUP,
                expenses = listOf(
                    expenseAt(uuid = "a", timestamp = "2026-10-28T09:00:00", groupUuid = GROUP, revision = 1),
                    expenseAt(uuid = "b", timestamp = "2026-10-30T19:45:00", groupUuid = GROUP, revision = 1),
                    expenseAt(uuid = "c", timestamp = "2026-10-29T12:00:00", groupUuid = GROUP, revision = 1),
                ),
            )
            database.expenseDao.storeGroupExpenses(
                groupUuid = OTHER_GROUP,
                expenses = listOf(expenseAt(uuid = "o", timestamp = "2026-10-31T08:00:00", groupUuid = OTHER_GROUP, revision = 1)),
            )
            database.expenseDao.upsertOneTimeExpense(expense = expenseAt(uuid = "t", timestamp = "2026-10-31T09:00:00"), revision = 0)

            assertEquals(listOf("b", "c"), database.expenseDao.getNewestFromGroupAsFlow(groupUuid = GROUP, limit = 2).first().map { it.expense.uuid })
            assertEquals(listOf("b", "c", "a"), database.expenseDao.getNewestFromGroupAsFlow(groupUuid = GROUP, limit = 20).first().map { it.expense.uuid })
        }
    }

    /** An expense [uuid] of Alice's at [timestamp], one-time unless [groupUuid] names its group. */
    private fun expenseAt(uuid: String, timestamp: String, groupUuid: String? = null, revision: Long? = null) =
        ExpenseWithBeneficiariesEntity(
            expense = ExpenseEntity(
                uuid = uuid,
                title = "Taxi",
                timestamp = timestamp,
                description = "",
                amount = "30.00",
                creditorUuid = ALICE,
                currency = CurrencyCatalog.defaultCurrency,
                groupUuid = groupUuid,
                revision = revision,
                authorUuid = ALICE.takeIf { groupUuid != null },
            ),
            beneficiaries = listOf(BeneficiaryEntity(expenseUuid = uuid, userUuid = BOB, share = "30.00", isShareFixed = false)),
        )

    /** Bob's flag on the expense, at [deletionRevision], saying [isDeleted]. */
    private fun deletionOf(deletionRevision: Long, isDeleted: Boolean) =
        GroupExpenseDeletionEntity(expenseUuid = EXPENSE, deletionRevision = deletionRevision, isDeleted = isDeleted, authorUuid = BOB)

    private fun expenseOf(
        groupUuid: String? = GROUP,
        revision: Long? = null,
        shares: List<Pair<String, String>> = listOf(ALICE to "15.00", BOB to "15.00"),
    ) = ExpenseWithBeneficiariesEntity(
        expense = ExpenseEntity(
            uuid = EXPENSE,
            title = "Taxi",
            timestamp = "2026-10-30T19:45:00",
            description = "",
            amount = "30.00",
            creditorUuid = ALICE,
            currency = CurrencyCatalog.defaultCurrency,
            groupUuid = groupUuid,
            revision = revision,
            authorUuid = ALICE.takeIf { groupUuid != null },
        ),
        beneficiaries = shares.map { (userUuid, share) ->
            BeneficiaryEntity(expenseUuid = EXPENSE, userUuid = userUuid, share = share, isShareFixed = false)
        },
    )
}
