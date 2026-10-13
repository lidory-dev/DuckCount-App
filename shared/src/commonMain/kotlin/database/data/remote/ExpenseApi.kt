package database.data.remote

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/** Carries `GET /expenses` on from where the page before ended. */
private const val CURSOR_QUERY = "cursor"

/** Where in the caller's feed `GET /expenses/changes` carries on from. */
private const val SINCE_QUERY = "since"

/**
 * Remote data source for one-time expenses (`groupUuid == null`).
 *
 * The listing is read whole once, a page at a time, and from then on only what changed,
 * see `expense.OneTimeExpenseSync.refresh`.
 */
class ExpenseApi(
    private val client: DuckCountClient,
) {
    /** The first page of the one-time expenses the caller is on, with where their feed stood. */
    suspend fun getOneTimeExpenses(): Result<ListOneTimeExpensesResponse, NetworkError> =
        client.get<ListOneTimeExpensesResponse>(route = DuckCountRoutes.Expense.EXPENSES)

    /** The page of the one-time expenses the caller is on that [cursor], the `next_cursor` of the page before, starts. */
    suspend fun getOneTimeExpenses(cursor: String): Result<ListOneTimeExpensesPage, NetworkError> =
        client.get<ListOneTimeExpensesPage>(
            route = DuckCountRoutes.Expense.EXPENSES,
            queryParams = mapOf(CURSOR_QUERY to cursor),
        )

    /**
     * One page of what changed in the one-time expenses the caller is on since [since], the
     * `feed_token` of the listing's first page or of the page of changes before.
     *
     * Handing [since] over tells the server the device has everything before it, which the
     * server then forgets: pass only a token stored with what it covers, and never one older
     * than a token already passed. The same token passed again, its answer lost, is answered
     * again. A `409` means the feed no longer holds what comes after [since], and the
     * expenses have to be listed again.
     */
    suspend fun getOneTimeExpenseChanges(
        since: String,
    ): Result<ListOneTimeExpenseChangesResponse, NetworkError> =
        client.get<ListOneTimeExpenseChangesResponse>(
            route = DuckCountRoutes.Expense.EXPENSE_CHANGES,
            queryParams = mapOf(SINCE_QUERY to since),
        )

    /** A `409` means an expense already has the UUID the nonce derives to. */
    suspend fun createOneTimeExpense(
        body: CreateOneTimeExpenseRequestBody,
    ): Result<Unit, NetworkError> =
        client.post<Unit, CreateOneTimeExpenseRequestBody>(
            route = DuckCountRoutes.Expense.EXPENSES,
            body = body,
        )

    suspend fun updateOneTimeExpense(
        uuid: String,
        body: OneTimeExpenseRequestBody,
    ): Result<Unit, NetworkError> =
        client.put<Unit, OneTimeExpenseRequestBody>(
            route = DuckCountRoutes.Expense.EXPENSE_BY_UUID,
            pathParams = mapOf("expense_uuid" to uuid),
            body = body,
        )

    /**
     * Writes the caller's own copy of the key to the expense [uuid]. A `404` means the
     * expense is gone, or the caller is no longer part of it.
     */
    suspend fun saveKeyCopy(
        uuid: String,
        keyCopy: EncryptedFieldDto,
    ): Result<Unit, NetworkError> =
        client.put<Unit, KeyCopyRequestBody>(
            route = DuckCountRoutes.Expense.EXPENSE_KEY_COPY,
            pathParams = mapOf("expense_uuid" to uuid),
            body = KeyCopyRequestBody(key_copy = keyCopy),
        )

    /**
     * The versions the one-time expense [uuid] had before each of its edits, newest first, as
     * the server stored them, see [OneTimeExpenseHistoryEntryDto]. A `404` means the expense is
     * gone, or the caller is no longer on it.
     */
    suspend fun getOneTimeExpenseHistory(
        uuid: String,
    ): Result<List<OneTimeExpenseHistoryEntryDto>, NetworkError> =
        client.get<List<OneTimeExpenseHistoryEntryDto>>(
            route = DuckCountRoutes.Expense.EXPENSE_HISTORY,
            pathParams = mapOf("expense_uuid" to uuid),
        )

    suspend fun deleteOneTimeExpense(
        uuid: String,
    ): Result<Unit, NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Expense.EXPENSE_BY_UUID,
            pathParams = mapOf("expense_uuid" to uuid),
        )
}
