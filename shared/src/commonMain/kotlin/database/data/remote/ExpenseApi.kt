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

    suspend fun deleteOneTimeExpense(
        uuid: String,
    ): Result<Unit, NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Expense.EXPENSE_BY_UUID,
            pathParams = mapOf("expense_uuid" to uuid),
        )
}
