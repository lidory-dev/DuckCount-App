package database.data.remote

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import networking.DuckCountClient
import networking.DuckCountRoutes
import networking.retryAfterSeconds
import util.Error
import util.NetworkError
import util.Result

/**
 * The most message UUIDs one acknowledgement may carry. The server refuses a larger batch
 * with a `400`.
 */
const val MAX_ACKNOWLEDGEMENT_BATCH = 100

/**
 * The header a `429` from `POST /messages` names the limit that refused the message in, see
 * [MessageLimitScope].
 */
private const val RATE_LIMIT_SCOPE_HEADER = "X-Rate-Limit-Scope"

/**
 * Remote data source for the caller's direct messages.
 *
 * The channel two users talk over outside of any group the server knows about, keyed by
 * recipient. Reading and acknowledging only ever reach the caller's own messages, and a
 * message the caller sends always goes out under their own UUID.
 */
class DirectMessageApi(
    private val client: DuckCountClient,
) {
    /** The messages waiting for the caller, oldest first. Each stays until acknowledged. */
    suspend fun getDirectMessages(): Result<ListDirectMessagesResponse, NetworkError> =
        client.get<ListDirectMessagesResponse>(
            route = DuckCountRoutes.Message.MESSAGES,
        )

    /**
     * Sends a message to [SendDirectMessageBody.recipientUserUuid].
     *
     * A recipient who deleted their account is a `410`, see
     * [SendDirectMessageError.RecipientDeleted]. A `404` is a recipient the server knows nothing
     * of, or the proxy in front of the API, which answers one for every route while the API is
     * being redeployed, and comes back as [NetworkError.NOT_FOUND], like any other refusal.
     * Sending is limited twice over, for what the caller sends this recipient and for what they
     * send everybody together, and a `429` says which of the two refused the message and when it
     * lets one through again, see [SendDirectMessageError.RateLimited].
     */
    suspend fun sendDirectMessage(body: SendDirectMessageBody): Result<Unit, SendDirectMessageError> {
        // Read off the response as it comes back, a NetworkError having no room for it.
        var refusal: SendDirectMessageError? = null

        val sent = client.execute<Unit> {
            client.httpClient.post(urlString = DuckCountRoutes.Message.MESSAGES) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }.also { response ->
                refusal = refusalOf(status = response.status, headers = response.headers)
            }
        }

        return when (sent) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(refusal ?: SendDirectMessageError.Network(sent.error))
        }
    }

    /**
     * Acknowledges messages the caller is done with, deleting them. At most
     * [MAX_ACKNOWLEDGEMENT_BATCH] at once.
     *
     * UUIDs that name nothing, or somebody else's messages, are ignored, so acknowledging
     * a message twice does no harm.
     */
    suspend fun acknowledgeDirectMessages(body: AcknowledgeDirectMessagesBody): Result<Unit, NetworkError> =
        client.post<Unit, AcknowledgeDirectMessagesBody>(
            route = DuckCountRoutes.Message.ACKNOWLEDGEMENTS,
            body = body,
        )
}

/** Why `POST /messages` did not take a message. */
sealed interface SendDirectMessageError : Error {

    /**
     * Refused for the limit [scope] names, which lets a message through again in
     * [retryAfterSeconds], the `Retry-After` of the `429`.
     */
    data class RateLimited(val retryAfterSeconds: Long, val scope: MessageLimitScope) : SendDirectMessageError

    /**
     * The recipient deleted their account, the `410` the server answers from the trace a deletion
     * leaves of it. Final: their UUID is never handed to anybody again.
     *
     * Told apart from the `404` of a recipient the server knows nothing of, which comes back as
     * [Network]: the proxy in front of the API answers one for every route while the API is being
     * redeployed, and a contact is dropped on this, see [message.DirectMessageSender].
     */
    data object RecipientDeleted : SendDirectMessageError

    /** The server could not be reached, or refused the call for any other reason. */
    data class Network(val error: NetworkError) : SendDirectMessageError
}

/**
 * Which of the server's two limits on `POST /messages` refused a message, as the `429` names it
 * in its `X-Rate-Limit-Scope` header.
 *
 * A pass sending one message to each of many people, as handing out the keys to an expense
 * does, needs it to know what to do next.
 */
enum class MessageLimitScope {

    /**
     * What the user may send this one recipient. Its refusal cost nothing of what they may
     * send everybody together, so the others can still be tried, though that may refuse
     * them in turn: it says nothing of how much of it is left.
     */
    RECIPIENT,

    /** What the user may send everybody together. Nothing more goes out until it reopens. */
    SENDER,
}

/**
 * What a response from `POST /messages` with [status] and [headers] says that a [NetworkError]
 * has no room for, or null when it says nothing more: the limit a `429` names, see
 * [toRateLimited], and the deletion a `410` tells of, see [SendDirectMessageError.RecipientDeleted].
 */
internal fun refusalOf(status: HttpStatusCode, headers: Headers): SendDirectMessageError? =
    when (status) {
        HttpStatusCode.TooManyRequests -> headers.toRateLimited()
        HttpStatusCode.Gone -> SendDirectMessageError.RecipientDeleted
        else -> null
    }

/**
 * The limit a `429` from `POST /messages` names: `Retry-After` in whole seconds, see
 * [retryAfterSeconds], and `recipient` or `sender` in `X-Rate-Limit-Scope`, as the server writes
 * them. A scope that reads otherwise is taken for the sender's, which holds back the most.
 */
internal fun Headers.toRateLimited(): SendDirectMessageError.RateLimited =
    SendDirectMessageError.RateLimited(
        retryAfterSeconds = retryAfterSeconds(),
        scope = if (get(RATE_LIMIT_SCOPE_HEADER) == "recipient") MessageLimitScope.RECIPIENT else MessageLimitScope.SENDER,
    )
