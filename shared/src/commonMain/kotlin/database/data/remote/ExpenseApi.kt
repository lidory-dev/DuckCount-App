package database.data.remote

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/**
 * Remote data source for one-time expenses (`groupUuid == null`).
 *
 * TODO: The server exposes no pagination and no delta endpoint: `getOneTimeExpenses`
 * returns the full authoritative list every time.
 */
class ExpenseApi(
    private val client: DuckCountClient,
) {
    suspend fun getOneTimeExpenses(): Result<List<ExpenseDto>, NetworkError> =
        client.get<List<ExpenseDto>>(route = DuckCountRoutes.Expense.EXPENSES)

    /** Returns the server-generated expense uuid (plain-text body). */
    suspend fun createOneTimeExpense(
        body: OneTimeExpenseRequestBody,
    ): Result<String, NetworkError> =
        client.post<String, OneTimeExpenseRequestBody>(
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

    suspend fun deleteOneTimeExpense(
        uuid: String,
    ): Result<Unit, NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Expense.EXPENSE_BY_UUID,
            pathParams = mapOf("expense_uuid" to uuid),
        )
}
