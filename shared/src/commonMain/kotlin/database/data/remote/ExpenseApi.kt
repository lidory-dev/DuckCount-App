package database.data.remote

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import networking.DuckCountClient
import networking.DuckCountRoutes
import util.Error
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

    /**
     * A `409` means an expense already has the UUID the nonce derives to, and a `410` that the
     * expense names people who deleted their account, see
     * [WriteOneTimeExpenseError.BeneficiariesDeleted].
     */
    suspend fun createOneTimeExpense(
        body: CreateOneTimeExpenseRequestBody,
    ): Result<Unit, WriteOneTimeExpenseError> =
        write {
            client.httpClient.post(urlString = DuckCountRoutes.Expense.EXPENSES) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * A `410` means the edit adds people who deleted their account, see
     * [WriteOneTimeExpenseError.BeneficiariesDeleted]. One the expense names already is no
     * addition, whether or not they deleted their account since.
     */
    suspend fun updateOneTimeExpense(
        uuid: String,
        body: OneTimeExpenseRequestBody,
    ): Result<Unit, WriteOneTimeExpenseError> =
        write {
            client.httpClient.put(
                urlString = DuckCountRoutes.Expense.EXPENSE_BY_UUID.replace(oldValue = ":expense_uuid", newValue = uuid),
            ) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /** Sends a write of a one-time expense, reading off the response what [writeRefusalOf] says. */
    private suspend fun write(request: suspend () -> HttpResponse): Result<Unit, WriteOneTimeExpenseError> {
        // Read off the response as it comes back, a NetworkError having no room for it.
        var refusal: WriteOneTimeExpenseError? = null

        val written = client.execute<Unit> {
            request().also { response ->
                refusal = writeRefusalOf(status = response.status) { response.body() }
            }
        }

        return when (written) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(refusal ?: WriteOneTimeExpenseError.Network(written.error))
        }
    }

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

    /**
     * Marks the caller's one-time expense [uuid] as paid back, or no longer. No edit: it spends
     * none of the edits the server allows, and leaves the history as it is. A `403` means the
     * caller is not its creditor, a `404` that the expense is gone or they are not on it.
     */
    suspend fun setOneTimeExpenseSettled(
        uuid: String,
        body: SetOneTimeExpenseSettledRequestBody,
    ): Result<Unit, NetworkError> =
        client.put<Unit, SetOneTimeExpenseSettledRequestBody>(
            route = DuckCountRoutes.Expense.EXPENSE_SETTLED,
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

/** Why `POST /expenses` or `PUT /expenses/{uuid}` did not take a one-time expense. */
sealed interface WriteOneTimeExpenseError : Error {

    /**
     * The server refused the expense for naming people who deleted their account, [userUuids],
     * sorted as the server sorts them: every one a new expense names, and only the ones an edit
     * adds, one the expense names already being kept, see [DeletedBeneficiariesResponse]. Nothing
     * was written.
     */
    data class BeneficiariesDeleted(val userUuids: List<String>) : WriteOneTimeExpenseError

    /** The server could not be reached, or refused the call for any other reason. */
    data class Network(val error: NetworkError) : WriteOneTimeExpenseError
}

/**
 * What a response with [status] to a write of a one-time expense says that a [NetworkError] has
 * no room for, or null when it says nothing more: who a `410` names, from the body [deleted]
 * reads, see [WriteOneTimeExpenseError.BeneficiariesDeleted]. Neither route answers one for
 * anything else.
 */
internal suspend fun writeRefusalOf(
    status: HttpStatusCode,
    deleted: suspend () -> DeletedBeneficiariesResponse,
): WriteOneTimeExpenseError? =
    when (status) {
        HttpStatusCode.Gone -> WriteOneTimeExpenseError.BeneficiariesDeleted(userUuids = deleted().deleted_user_uuids)
        else -> null
    }
