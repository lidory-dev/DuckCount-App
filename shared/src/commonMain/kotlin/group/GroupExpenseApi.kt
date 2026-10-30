package group

import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import networking.CURSOR_QUERY
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.retryAfterSeconds
import util.NetworkError
import util.Result

/** Where in the caller's feed of a group's expenses `GET /groups/{group_uuid}/expenses/changes` carries on from. */
private const val SINCE_QUERY = "since"

/** What the server answers, in plain text, a write to a group the caller is no current member of. */
private const val GROUP_NOT_FOUND = "Group not found."

/** Reads the JSON bodies of a `409`, and nothing more of them than this build knows. */
private val conflictJson = Json { ignoreUnknownKeys = true }

/**
 * Remote data source for the expenses of the caller's groups: creating one, updating one with its
 * next version, a deletion and a restore among them, reading a group's listing whole and then what
 * changed in it, and the versions of an expense the server keeps.
 *
 * Every version is sealed on its author's device under the group's key, which the server never
 * holds, and signed by them, see [sealGroupExpense], so the server learns which groups have how
 * many expenses, and when they change, and nothing of what they say: not who paid, nor who
 * benefits. Current members alone may read or write any of it, a former member hearing `404`.
 */
class GroupExpenseApi(
    private val client: DuckCountClient,
) {
    /**
     * The first page of the expenses of the group [groupUuid], newest first, with where the
     * caller's feed of them stood, which this very request makes when the caller has none yet. A
     * `404` is a caller who is no current member of the group, or the proxy in front of the server
     * answering as it is redeployed.
     */
    suspend fun getExpenses(groupUuid: String): Result<ListGroupExpensesResponse, NetworkError> =
        client.get<ListGroupExpensesResponse>(
            route = DuckCountRoutes.Group.EXPENSES,
            pathParams = mapOf("group_uuid" to groupUuid),
        )

    /** The page of the expenses of the group [groupUuid] that [cursor], the `next_cursor` of the page before, starts. */
    suspend fun getExpenses(groupUuid: String, cursor: String): Result<ListGroupExpensesPage, NetworkError> =
        client.get<ListGroupExpensesPage>(
            route = DuckCountRoutes.Group.EXPENSES,
            pathParams = mapOf("group_uuid" to groupUuid),
            queryParams = mapOf(CURSOR_QUERY to cursor),
        )

    /**
     * One page of what changed in the expenses of the group [groupUuid] since [since], the
     * `feed_token` of the listing's first page or of the page of changes before.
     *
     * Handing [since] over tells the server the device has everything before it, which it then
     * forgets: pass only a token stored with what it covers, and never one older than a token
     * already passed, as for one-time expenses, see [database.data.remote.ExpenseApi]. A `409`
     * means the feed no longer holds what comes after [since], the caller's first listing of the
     * group, which makes the feed, included, and the group's expenses have to be listed again.
     */
    suspend fun getChanges(groupUuid: String, since: String): Result<ListGroupExpenseChangesResponse, NetworkError> =
        client.get<ListGroupExpenseChangesResponse>(
            route = DuckCountRoutes.Group.EXPENSE_CHANGES,
            pathParams = mapOf("group_uuid" to groupUuid),
            queryParams = mapOf(SINCE_QUERY to since),
        )

    /**
     * The versions of the expense [expenseUuid] of the group [groupUuid] the server keeps, see
     * [GroupExpenseHistoryResponse]: the one it stands at and the nine its updates last replaced,
     * newest first, each sealed under its own generation of the group's key, with when the server
     * took it. A `404` is a caller who is no current member of the group, an expense the group does
     * not have, or the proxy in front of the server answering as it is redeployed.
     */
    suspend fun history(
        groupUuid: String,
        expenseUuid: String,
    ): Result<GroupExpenseHistoryResponse, NetworkError> =
        client.get<GroupExpenseHistoryResponse>(
            route = DuckCountRoutes.Group.EXPENSE_HISTORY,
            pathParams = mapOf("group_uuid" to groupUuid, "expense_uuid" to expenseUuid),
        )

    /** Creates the expense of the group [groupUuid] that [body]'s nonce names, see [GroupExpenseWriteAnswer]. */
    suspend fun create(groupUuid: String, body: CreateGroupExpenseBody): GroupExpenseWriteAnswer =
        write {
            client.httpClient.post(urlString = DuckCountRoutes.Group.EXPENSES.replace(":group_uuid", groupUuid)) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * Replaces the expense [expenseUuid] of the group [groupUuid] with the version [body] carries,
     * see [GroupExpenseWriteAnswer].
     */
    suspend fun update(groupUuid: String, expenseUuid: String, body: UpdateGroupExpenseBody): GroupExpenseWriteAnswer =
        write {
            client.httpClient.put(
                urlString = DuckCountRoutes.Group.EXPENSE
                    .replace(":group_uuid", groupUuid)
                    .replace(":expense_uuid", expenseUuid),
            ) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /** What the server answered [request], a write of a group expense, see [GroupExpenseWriteAnswer]. */
    private suspend fun write(request: suspend () -> HttpResponse): GroupExpenseWriteAnswer {
        // Read off the response as it comes back, a NetworkError having no room for it.
        var refusal: GroupExpenseWriteAnswer? = null

        val sent = client.execute<Unit> {
            request().also { response ->
                refusal = writeRefusalOf(status = response.status, headers = response.headers) { response.bodyAsText() }
            }
        }

        return when (sent) {
            is Result.Success -> GroupExpenseWriteAnswer.Written
            is Result.Error -> refusal ?: GroupExpenseWriteAnswer.Failed(error = sent.error)
        }
    }
}

/**
 * What the server answered `POST /groups/{group_uuid}/expenses` or `PUT
 * /groups/{group_uuid}/expenses/{expense_uuid}`. The server writes nothing unless it takes the
 * version whole, so every refusal is final for that request, and only [Failed] leaves open whether
 * it was stored.
 *
 * The server checks, in this order: the content's size, its form, the caller's membership of the
 * group, the generation of the key it is sealed under, then, for an update, that the group has the
 * expense and the revision, and for a create, the nonce, and last the caller's allowance. So a
 * resend hears whatever came first of these, whatever became of the rest since.
 */
sealed interface GroupExpenseWriteAnswer {

    /** The empty `201` of a create or `200` of an update: the server holds the version. */
    data object Written : GroupExpenseWriteAnswer

    /**
     * The plain `409` of a create whose nonce the caller sent already: an earlier send of this very
     * create, whose answer never came back, every expense being given a nonce of its own. As good as
     * [Written].
     */
    data object AlreadyExists : GroupExpenseWriteAnswer

    /**
     * The JSON `409` of a version sealed under a generation of the group's key that is not its
     * current one, [keyGeneration]: a rotation this device has yet to read the key of, see
     * [GroupSync]. Checked ahead of the expense, its revision and the nonce.
     */
    data class KeyGenerationNotCurrent(val keyGeneration: Long) : GroupExpenseWriteAnswer

    /**
     * The JSON `409` of an update whose revision is not one past the one the server holds,
     * [revision]: another member's update came first, or an earlier send of this very one, its
     * answer lost.
     */
    data class RevisionNotNext(val revision: Long) : GroupExpenseWriteAnswer

    /**
     * The `404` of a caller who is no current member of the group: removed, or left, as the next
     * group sync reads.
     */
    data object GroupNotFound : GroupExpenseWriteAnswer

    /**
     * The `429` of the server's limit on the group expenses one user writes, creates and updates
     * together, every group together, which lets one through again in [retryAfterSeconds], the
     * `Retry-After` it carries. Nothing was written: the limit is only counted once the write would
     * be stored.
     */
    data class RateLimited(val retryAfterSeconds: Long) : GroupExpenseWriteAnswer

    /**
     * Any other answer: the `400` or `413` of a body the server could not take, which it refuses
     * before writing anything, or no answer that says what became of the write, the request having
     * never left, or the version been stored and the answer lost, the proxy's `404` as the server is
     * redeployed among them.
     */
    data class Failed(val error: NetworkError) : GroupExpenseWriteAnswer
}

/**
 * What a write of a group expense that answered with [status] and [headers] comes to, for the
 * answers read off the response itself, or null for the others: the `409`s, told apart by their
 * body, which [body] reads, JSON for a generation or a revision and plain text for a nonce; the
 * `404` of a group, in a plain text of its own, any other being the proxy's; and the `429`, with
 * its `Retry-After`.
 *
 * Never the `404` of an expense the group does not have: an update is only ever of one this device
 * shows, which the server took in that group, and no expense of a group goes but with the group
 * itself, which the server answers the `404` of a group for first.
 */
internal suspend fun writeRefusalOf(
    status: HttpStatusCode,
    headers: Headers,
    body: suspend () -> String,
): GroupExpenseWriteAnswer? =
    when (status) {
        HttpStatusCode.Conflict -> conflictOf(body = body())

        HttpStatusCode.NotFound -> when (body().trim()) {
            GROUP_NOT_FOUND -> GroupExpenseWriteAnswer.GroupNotFound
            else -> null
        }

        HttpStatusCode.TooManyRequests -> GroupExpenseWriteAnswer.RateLimited(
            retryAfterSeconds = headers.retryAfterSeconds(),
        )

        else -> null
    }

/**
 * Which `409` [body] is: one naming the group's current generation, one naming the expense's
 * revision, or the plain text of a nonce sent already, which only a create answers. A JSON body that
 * does not read as the API writes it throws, which the client reads as one it could not read, see
 * [networking.Client.execute].
 */
private fun conflictOf(body: String): GroupExpenseWriteAnswer {
    val fields = runCatching { conflictJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return GroupExpenseWriteAnswer.AlreadyExists

    return if ("key_generation" in fields) {
        GroupExpenseWriteAnswer.KeyGenerationNotCurrent(
            keyGeneration = conflictJson.decodeFromJsonElement(KeyGenerationConflictResponse.serializer(), fields)
                .keyGeneration,
        )
    } else {
        GroupExpenseWriteAnswer.RevisionNotNext(
            revision = conflictJson.decodeFromJsonElement(RevisionConflictResponse.serializer(), fields).revision,
        )
    }
}
