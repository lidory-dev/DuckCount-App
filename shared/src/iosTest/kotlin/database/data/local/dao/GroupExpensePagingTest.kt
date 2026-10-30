package database.data.local.dao

import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.domain.CurrencyCatalog
import database.withInMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val ALICE = "11111111-1111-1111-1111-111111111111"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * The pages of a group's list of expenses, see [ExpenseDao.groupPagingSource], read from the
 * database itself one after another, as the list reads them.
 */
class GroupExpensePagingTest {

    @Test
    fun `a group's list holds its own expenses newest first and the deleted ones too`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.insert(expenseOf(uuid = "older", timestamp = "2026-10-29T08:00:00"))
            database.expenseDao.insert(expenseOf(uuid = "deleted", timestamp = "2026-10-30T19:45:00", isDeleted = true))
            database.expenseDao.insert(expenseOf(uuid = "newest", timestamp = "2026-10-30T21:10:00"))

            assertEquals(
                listOf("newest", "deleted", "older"),
                database.expenseDao.groupPagingSource(groupUuid = GROUP).readAll(pageSize = 20),
            )
        }
    }

    @Test
    fun `a group's list holds neither one-time expenses nor another group's`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.insert(expenseOf(uuid = "group", timestamp = "2026-10-29T08:00:00"))
            database.expenseDao.insert(expenseOf(uuid = "one-time", timestamp = "2026-10-30T19:45:00", groupUuid = null))
            database.expenseDao.insert(expenseOf(uuid = "other", timestamp = "2026-10-30T21:10:00", groupUuid = OTHER_GROUP))

            assertEquals(listOf("group"), database.expenseDao.groupPagingSource(groupUuid = GROUP).readAll(pageSize = 20))
        }
    }

    /**
     * Read by offset, a page that ends among expenses of the same time would show one of them again
     * on the next page, and another on none, were their order left to the database. Stored in an
     * order other than their UUIDs', so that the database's own would not pass for it.
     */
    @Test
    fun `expenses of the same time come once each and in one order whatever the pages`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.expenseDao.insert(expenseOf(uuid = "newest", timestamp = "2026-10-30T21:10:00"))
            listOf("d", "b", "e", "a", "c").forEach { uuid ->
                database.expenseDao.insert(expenseOf(uuid = uuid, timestamp = "2026-10-30T19:45:00"))
            }
            database.expenseDao.insert(expenseOf(uuid = "oldest", timestamp = "2026-10-29T08:00:00"))

            (1..4).forEach { pageSize ->
                assertEquals(
                    listOf("newest", "a", "b", "c", "d", "e", "oldest"),
                    database.expenseDao.groupPagingSource(groupUuid = GROUP).readAll(pageSize = pageSize),
                    "Pages of $pageSize",
                )
            }
        }
    }

    /**
     * The UUIDs of every expense the list shows, read [pageSize] at a time from the first page to the
     * last, as the list reads them when scrolled to its end.
     */
    private suspend fun PagingSource<Int, ExpenseWithBeneficiariesEntity>.readAll(pageSize: Int): List<String> {
        val uuids = mutableListOf<String>()
        var params: LoadParams<Int> = LoadParams.Refresh(key = null, loadSize = pageSize, placeholdersEnabled = false)

        while (true) {
            val page = assertIs<LoadResult.Page<Int, ExpenseWithBeneficiariesEntity>>(load(params))

            uuids += page.data.map { it.expense.uuid }

            val nextKey = page.nextKey ?: return uuids

            params = LoadParams.Append(key = nextKey, loadSize = pageSize, placeholdersEnabled = false)
        }
    }

    private fun expenseOf(
        uuid: String,
        timestamp: String,
        groupUuid: String? = GROUP,
        isDeleted: Boolean = false,
    ) = ExpenseEntity(
        uuid = uuid,
        title = "Taxi",
        timestamp = timestamp,
        description = "",
        amount = "30.00",
        creditorUuid = ALICE,
        currency = CurrencyCatalog.defaultCurrency,
        groupUuid = groupUuid,
        isDeleted = isDeleted,
        revision = 1,
        authorUuid = ALICE.takeIf { groupUuid != null },
    )
}
