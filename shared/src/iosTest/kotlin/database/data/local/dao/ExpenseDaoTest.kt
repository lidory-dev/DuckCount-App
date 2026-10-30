package database.data.local.dao

import database.data.local.entity.BeneficiaryEntity
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.domain.CurrencyCatalog
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

private const val EXPENSE = "eeeeeeee-eeee-8eee-8eee-eeeeeeeeeeee"

/**
 * What the database keeps of a group expense written over: its beneficiaries replaced whole, and
 * stored over nothing of another kind, nor of another group, see [ExpenseDao.storeGroupExpenses],
 * run on the database itself.
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
