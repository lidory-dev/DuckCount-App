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
 * next version, an edit, deleting or restoring one with its next deletion flag, reading a group's
 * listing whole and then what changed in it, and the versions of an expense the server keeps.
 *
 * Every version and every flag is sealed on its author's device under the group's key, which the
 * server never holds, and signed by them, see [sealGroupExpense] and [sealGroupExpenseDeletion], so
 * the server learns which groups have how many expenses, when they change, and how often each was
 * deleted or restored, which usually tells it whether it is deleted now, and nothing of what they
 * say: not who paid, nor who benefits. Current members alone may read or write any of it, a
 * former member hearing `404`.
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
     * The versions of the expense [expenseUuid] of the group [groupUuid], every one of which the
     * server keeps, see [GroupExpenseHistoryResponse]: the one it stands at and those its updates
     * replaced, newest first, ten at most, each sealed under its own generation of the group's key,
     * with when the server took it. A `404` is a caller who is no current member of the group, an
     * expense the group does not have, or the proxy in front of the server answering as it is
     * redeployed.
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
        write(kind = GroupExpenseWriteKind.CREATE) {
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
        write(kind = GroupExpenseWriteKind.UPDATE) {
            client.httpClient.put(urlString = DuckCountRoutes.Group.EXPENSE.pathOf(groupUuid, expenseUuid)) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * Deletes the expense [expenseUuid] of the group [groupUuid], or restores it, with the flag
     * [body] carries, see [GroupExpenseDeletionAnswer].
     */
    suspend fun setDeletion(
        groupUuid: String,
        expenseUuid: String,
        body: SetGroupExpenseDeletionBody,
    ): GroupExpenseDeletionAnswer =
        answerOf(
            request = {
                client.httpClient.put(urlString = DuckCountRoutes.Group.EXPENSE_DELETION.pathOf(groupUuid, expenseUuid)) {
                    setBody(body)
                    contentType(ContentType.Application.Json)
                }
            },
            refusalOf = { response ->
                deletionRefusalOf(status = response.status, headers = response.headers) { response.bodyAsText() }
            },
            written = GroupExpenseWriteAnswer.Written,
            failed = { GroupExpenseWriteAnswer.Failed(error = it) },
        )

    /** What the server answered [request], a write of a group expense of [kind], see [GroupExpenseWriteAnswer]. */
    private suspend fun write(kind: GroupExpenseWriteKind, request: suspend () -> HttpResponse): GroupExpenseWriteAnswer =
        answerOf(
            request = request,
            refusalOf = { response ->
                writeRefusalOf(status = response.status, headers = response.headers, kind = kind) { response.bodyAsText() }
            },
            written = GroupExpenseWriteAnswer.Written,
            failed = { GroupExpenseWriteAnswer.Failed(error = it) },
        )

    /**
     * What the server answered [request]: [written] for its `2xx`, what [refusalOf] reads off the
     * response for the answers it reads, and [failed] for any other.
     */
    private suspend fun <A> answerOf(
        request: suspend () -> HttpResponse,
        refusalOf: suspend (response: HttpResponse) -> A?,
        written: A,
        failed: (error: NetworkError) -> A,
    ): A {
        // Read off the response as it comes back, a NetworkError having no room for it.
        var refusal: A? = null

        val sent = client.execute<Unit> {
            request().also { response -> refusal = refusalOf(response) }
        }

        return when (sent) {
            is Result.Success -> written
            is Result.Error -> refusal ?: failed(sent.error)
        }
    }
}

/** This route with the group [groupUuid] and its expense [expenseUuid] in it. */
private fun String.pathOf(groupUuid: String, expenseUuid: String) =
    replace(":group_uuid", groupUuid).replace(":expense_uuid", expenseUuid)

/** Which write of a version of a group expense a request is, which tells its plain text `409`s apart, see [writeRefusalOf]. */
internal enum class GroupExpenseWriteKind {
    /** `POST /groups/{group_uuid}/expenses`, the expense's first version. */
    CREATE,

    /** `PUT /groups/{group_uuid}/expenses/{expense_uuid}`, its next version, an edit. */
    UPDATE,
}

/**
 * What the server answered `POST /groups/{group_uuid}/expenses` or `PUT
 * /groups/{group_uuid}/expenses/{expense_uuid}`. The server writes nothing unless it takes the
 * version whole, so every refusal is final for that request, and only [Failed] leaves open whether
 * it was stored.
 *
 * The server checks, in this order: the content's size, its form, the caller's membership of the
 * group, the generation of the key it is sealed under, then, for an update, that the group has the
 * expense, that it is not at its last revision, and the revision, and for a create, the nonce, and
 * last the caller's allowance. So a resend hears whatever came first of these, whatever became of
 * the rest since.
 *
 * Those a deletion flag's write is answered too are [GroupExpenseDeletionAnswer]s as well.
 */
sealed interface GroupExpenseWriteAnswer {

    /** The empty `201` of a create, or `200` of an update or of a deletion flag: the server holds what was sent. */
    data object Written : GroupExpenseWriteAnswer, GroupExpenseDeletionAnswer

    /**
     * The plain `409` of a create whose nonce the caller sent already: an earlier send of this very
     * create, whose answer never came back, every expense being given a nonce of its own. As good as
     * [Written].
     */
    data object AlreadyExists : GroupExpenseWriteAnswer

    /**
     * The JSON `409` of a version sealed under a generation of the group's key that is not its
     * current one, [keyGeneration]: a Commit this device has yet to read the key it leads to of, see
     * [GroupSync]. Checked ahead of the expense, its revision and the nonce.
     */
    data class KeyGenerationNotCurrent(val keyGeneration: Long) : GroupExpenseWriteAnswer, GroupExpenseDeletionAnswer

    /**
     * The JSON `409` of an update whose revision is not one past the one the server holds,
     * [revision]: another member's update came first, or an earlier send of this very one, its
     * answer lost.
     */
    data class RevisionNotNext(val revision: Long) : GroupExpenseWriteAnswer

    /**
     * The plain `409` of an update of an expense the server holds at its last revision, its every
     * edit made, see [app.lidory.duckcount.ExpenseLimits.MAX_GROUP_EXPENSE_EDITS]: by another
     * member, or by an earlier send of this very update, its answer lost, the limit being checked
     * ahead of the revision. Nothing was written, and no allowance spent.
     */
    data object EditLimitReached : GroupExpenseWriteAnswer

    /**
     * The `404` of a caller who is no current member of the group: removed, or left, as the next
     * group sync reads.
     */
    data object GroupNotFound : GroupExpenseWriteAnswer, GroupExpenseDeletionAnswer

    /**
     * The `429` of the server's limit on the group expenses one user writes, creates, updates and
     * deletion flags together, every group together, which lets one through again in
     * [retryAfterSeconds], the `Retry-After` it carries. Nothing was written: the limit is only
     * counted once the write would be stored.
     */
    data class RateLimited(val retryAfterSeconds: Long) : GroupExpenseWriteAnswer, GroupExpenseDeletionAnswer

    /**
     * Any other answer: the `400` or `413` of a body the server could not take, which it refuses
     * before writing anything, or no answer that says what became of the write, the request having
     * never left, or the version been stored and the answer lost, the proxy's `404` as the server is
     * redeployed among them.
     */
    data class Failed(val error: NetworkError) : GroupExpenseWriteAnswer, GroupExpenseDeletionAnswer
}

/**
 * What the server answered `PUT /groups/{group_uuid}/expenses/{expense_uuid}/deletion`, see
 * [GroupExpenseApi.setDeletion]: [GroupExpenseWriteAnswer.Written], its empty `200`, the server
 * holding the flag, or the refusals a version hears too, as [GroupExpenseWriteAnswer] tells them,
 * or [DeletionRevisionNotNext]. The server writes nothing unless it takes the flag whole.
 *
 * It checks, in this order: the content's size, its form, the caller's membership of the group, the
 * generation of the key it is sealed under, that the group has the expense, the flag's revision,
 * and last the caller's allowance, the one versions draw on.
 */
sealed interface GroupExpenseDeletionAnswer {

    /**
     * The JSON `409` of a flag whose revision is not one past the one the server holds,
     * [deletionRevision], 0 before the expense's first deletion: another member's flag came first,
     * or an earlier send of this very one, its answer lost.
     */
    data class DeletionRevisionNotNext(val deletionRevision: Long) : GroupExpenseDeletionAnswer
}

/**
 * What a write of a version of a group expense, of [kind], that answered with [status] and
 * [headers] comes to, for the answers read off the response itself, or null for the others: the
 * `409`s, told apart by their body, which [body] reads, JSON for a generation or a revision, and
 * plain text for a nonce, which only a create answers, or for the last revision, which only an
 * update does; the `404` of a group, in a plain text of its own, any other being the proxy's; and
 * the `429`, with its `Retry-After`.
 *
 * Never the `404` of an expense the group does not have: an update is only ever of one this device
 * shows, which the server took in that group, and no expense of a group goes but with the group
 * itself, which the server answers the `404` of a group for first.
 */
internal suspend fun writeRefusalOf(
    status: HttpStatusCode,
    headers: Headers,
    kind: GroupExpenseWriteKind,
    body: suspend () -> String,
): GroupExpenseWriteAnswer? =
    when (status) {
        HttpStatusCode.Conflict -> conflictOf(body = body(), kind = kind)

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
 * What a write of a deletion flag that answered with [status] and [headers] comes to, as
 * [writeRefusalOf] tells a version's: its `409`s are JSON alike, naming the group's current
 * generation or the flag's revision, which [body] reads, and it has the same `404`s and `429`.
 */
internal suspend fun deletionRefusalOf(
    status: HttpStatusCode,
    headers: Headers,
    body: suspend () -> String,
): GroupExpenseDeletionAnswer? =
    when (status) {
        HttpStatusCode.Conflict -> deletionConflictOf(body = body())

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
 * Which `409` [body] is, for a write of [kind]: one naming the group's current generation, which
 * either kind answers, one naming the expense's revision, which only an update does, or plain text,
 * a nonce sent already for a create and the last revision for an update. A JSON body that does not
 * read as the API writes it throws, which the client reads as one it could not read, see
 * [networking.Client.execute].
 */
private fun conflictOf(body: String, kind: GroupExpenseWriteKind): GroupExpenseWriteAnswer {
    val fields = runCatching { conflictJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return when (kind) {
            GroupExpenseWriteKind.CREATE -> GroupExpenseWriteAnswer.AlreadyExists
            GroupExpenseWriteKind.UPDATE -> GroupExpenseWriteAnswer.EditLimitReached
        }

    return if (kind == GroupExpenseWriteKind.CREATE || "key_generation" in fields) {
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

/**
 * Which `409` of a deletion flag [body] is: one naming the group's current generation, or one
 * naming the flag's revision, both JSON. Anything else throws, as a JSON body that does not read
 * as the API writes it does, see [conflictOf].
 */
private fun deletionConflictOf(body: String): GroupExpenseDeletionAnswer {
    val fields = conflictJson.decodeFromString(JsonObject.serializer(), body)

    return if ("key_generation" in fields) {
        GroupExpenseWriteAnswer.KeyGenerationNotCurrent(
            keyGeneration = conflictJson.decodeFromJsonElement(KeyGenerationConflictResponse.serializer(), fields)
                .keyGeneration,
        )
    } else {
        GroupExpenseDeletionAnswer.DeletionRevisionNotNext(
            deletionRevision = conflictJson.decodeFromJsonElement(DeletionRevisionConflictResponse.serializer(), fields)
                .deletionRevision,
        )
    }
}
