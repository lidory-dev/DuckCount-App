package group

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.retryAfterSeconds
import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * The most message UUIDs one acknowledgement in a group may carry. The server refuses a larger
 * batch with a `400`.
 */
const val MAX_GROUP_ACKNOWLEDGEMENT_BATCH = 100

/**
 * How long a request carrying a group's Welcome is given, against the 5 s every other request
 * gets, see [DuckCountClient]: the Welcome carries the group's whole ratchet tree, some 400 KB at
 * a hundred members, which a slow connection takes far longer than that to move. The creator
 * uploads it, see [GroupApi.createGroup], a creation that runs out of time being sent again all
 * the same, see [GroupCreationAnswer.Failed], and every invitee downloads that same Welcome, see
 * [GroupApi.getGroupMessages].
 */
private const val WELCOME_TIMEOUT_MILLIS = 60_000L

/**
 * Remote data source for the caller's groups: creating one, listing them, and the messages
 * waiting in each, the Welcome that lets an invitee in among them.
 *
 * Every field of a group is sealed on the creator's device under a key the server never holds,
 * see [GroupFields.seal], and every message is MLS, so the server learns who is in which group
 * and nothing of what they share.
 */
class GroupApi(
    private val client: DuckCountClient,
) {
    /**
     * Creates the group whose UUID [CreateGroupBody.nonce] derives from the caller's, the caller
     * owning it, and hands its Welcome to every invitee, see [GroupCreationAnswer] for what the
     * server can answer.
     */
    suspend fun createGroup(body: CreateGroupBody): GroupCreationAnswer {
        // Read off the response as it comes back, a NetworkError having no room for it.
        var refusal: GroupCreationAnswer? = null

        val sent = client.execute<Unit> {
            client.httpClient.post(urlString = DuckCountRoutes.Group.GROUPS) {
                timeout { requestTimeoutMillis = WELCOME_TIMEOUT_MILLIS }
                setBody(body)
                contentType(ContentType.Application.Json)
            }.also { response ->
                refusal = creationRefusalOf(status = response.status, headers = response.headers) { response.body() }
            }
        }

        return when (sent) {
            is Result.Success -> GroupCreationAnswer.Created
            is Result.Error -> refusal ?: creationAnswerOf(error = sent.error)
        }
    }

    /**
     * The groups the caller is a member of, those they were invited to and have not joined yet
     * included.
     */
    suspend fun getGroups(): Result<List<GroupDto>, NetworkError> =
        client.get<List<GroupDto>>(
            route = DuckCountRoutes.Group.GROUPS,
        )

    /**
     * The messages waiting for the caller in the group [groupUuid], oldest first, the Welcome of a
     * group they have yet to join among them, which is why they get as long as a creation does.
     * Each stays until acknowledged, see [acknowledgeGroupMessages].
     *
     * The server checks no membership, it lists by recipient: a group the caller is not a member
     * of, like one with nothing waiting for them, answers an empty list.
     */
    suspend fun getGroupMessages(groupUuid: String): Result<ListGroupMessagesResponse, NetworkError> =
        client.execute {
            client.httpClient.get(urlString = DuckCountRoutes.Group.MESSAGES.replace(":group_uuid", groupUuid)) {
                timeout { requestTimeoutMillis = WELCOME_TIMEOUT_MILLIS }
            }
        }

    /**
     * Acknowledges messages the caller is done with in the group [groupUuid], in as many requests
     * of at most [MAX_GROUP_ACKNOWLEDGEMENT_BATCH] as it takes, stopping at the first that fails:
     * what was not acknowledged is handed back by the next listing.
     *
     * UUIDs that name nothing, or messages not waiting for the caller, are ignored, so
     * acknowledging a message twice does no harm.
     */
    suspend fun acknowledgeGroupMessages(groupUuid: String, messageUuids: List<String>): EmptyResult<NetworkError> {
        messageUuids.chunked(MAX_GROUP_ACKNOWLEDGEMENT_BATCH).forEach { batch ->
            val acknowledgement = client.post<Unit, AcknowledgeGroupMessagesBody>(
                route = DuckCountRoutes.Group.ACKNOWLEDGEMENTS,
                pathParams = mapOf("group_uuid" to groupUuid),
                body = AcknowledgeGroupMessagesBody(messageUuids = batch),
            )

            if (acknowledgement is Result.Error) return acknowledgement
        }

        return Result.Success(Unit)
    }
}

/**
 * What the server answered `POST /groups`, as far as what became of the group goes.
 *
 * The server writes nothing unless it takes the whole creation, so every refusal is final for
 * that request, and only [Failed] leaves open whether the group was stored.
 */
sealed interface GroupCreationAnswer {

    /**
     * The empty `201`: the server took the group, under the UUID the creator's device derived
     * already, so it has nothing to hand back.
     */
    data object Created : GroupCreationAnswer

    /**
     * The caller already created a group with this nonce: an earlier send of this very creation,
     * whose answer never came back, since every group gets a nonce of its own. As good as
     * [Created].
     *
     * Checked ahead of [DeletedInvitees] and [RateLimited], so that a resend of a creation the
     * server stored hears so whatever became of the invitees or the limit since.
     */
    data object AlreadyExists : GroupCreationAnswer

    /**
     * The `410` naming every invitee with no account, whether they deleted it or it never
     * existed: none of them could ever join, so nothing was written.
     */
    data class DeletedInvitees(val userUuids: List<String>) : GroupCreationAnswer

    /**
     * The `429` of the server's limit on the groups one user creates, which lets one through
     * again in [retryAfterSeconds], the `Retry-After` it carries. Nothing was written: the limit
     * is only counted once the creation would be stored.
     */
    data class RateLimited(val retryAfterSeconds: Long) : GroupCreationAnswer

    /**
     * The server refused the request itself, whose body it could not take: nothing was written,
     * and the same body would be refused again.
     */
    data class Refused(val error: NetworkError) : GroupCreationAnswer

    /**
     * No answer that says what became of the group: the request may never have left, or the
     * group may have been stored and the answer lost on the way back, the proxy's `404` while the
     * API is being redeployed and a `401` that a refresh may still turn around included. Only
     * sending it again can tell.
     */
    data class Failed(val error: NetworkError) : GroupCreationAnswer
}

/**
 * What a `POST /groups` answered with [status] and [headers] comes to, for the answers read off
 * the response itself, or null for the others, see [creationAnswerOf]: the `410`, naming whom
 * its body, which [deleted] reads, says has no account, and the `429`, with its `Retry-After`.
 */
internal suspend fun creationRefusalOf(
    status: HttpStatusCode,
    headers: Headers,
    deleted: suspend () -> DeletedInviteesResponse,
): GroupCreationAnswer? =
    when (status) {
        HttpStatusCode.Gone -> GroupCreationAnswer.DeletedInvitees(userUuids = deleted().deletedUserUuids)

        HttpStatusCode.TooManyRequests -> GroupCreationAnswer.RateLimited(
            retryAfterSeconds = headers.retryAfterSeconds(),
        )

        else -> null
    }

/**
 * What a `POST /groups` that failed with [error] answered, for the answers whose response carries
 * nothing more: the `410` and `429` are read off the response itself, see [creationRefusalOf].
 *
 * A `400`, `413` or `422` is the server refusing the body, which it does before writing anything.
 */
internal fun creationAnswerOf(error: NetworkError): GroupCreationAnswer =
    when (error) {
        NetworkError.CONFLICT -> GroupCreationAnswer.AlreadyExists

        NetworkError.BAD_REQUEST,
        NetworkError.PAYLOAD_TOO_LARGE,
        NetworkError.UNPROCESSABLE_ENTITY,
        -> GroupCreationAnswer.Refused(error = error)

        else -> GroupCreationAnswer.Failed(error = error)
    }
