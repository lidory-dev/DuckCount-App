package database.data.remote

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import networking.DuckCountClient
import networking.DuckCountRoutes
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
 * How long a `429` whose `Retry-After` does not read as the server writes it is taken to hold
 * for: a minute, the shortest window the server has. A resend that comes too early is refused
 * again, with the headers that say when.
 */
private const val UNREAD_RETRY_AFTER_SECONDS = 60L

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
     * A recipient that does not exist, their account deleted for instance, is a `404`.
     * Sending is limited twice over, for what the caller sends this recipient and for what
     * they send everybody together, and a `429` says which of the two refused the message and
     * when it lets one through again, see [SendDirectMessageError.RateLimited].
     */
    suspend fun sendDirectMessage(body: SendDirectMessageBody): Result<Unit, SendDirectMessageError> {
        // Read off the response as it comes back, a NetworkError having no room for it.
        var rateLimited: SendDirectMessageError.RateLimited? = null

        val sent = client.execute<Unit> {
            client.httpClient.post(urlString = DuckCountRoutes.Message.MESSAGES) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }.also { response ->
                if (response.status == HttpStatusCode.TooManyRequests) {
                    rateLimited = response.headers.toRateLimited()
                }
            }
        }

        return when (sent) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(rateLimited ?: SendDirectMessageError.Network(sent.error))
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
 * The limit a `429` from `POST /messages` names: `Retry-After` in whole seconds, and
 * `recipient` or `sender` in `X-Rate-Limit-Scope`, as the server writes them. A scope that
 * reads otherwise is taken for the sender's, which holds back the most, and a `Retry-After`
 * for [UNREAD_RETRY_AFTER_SECONDS].
 */
internal fun Headers.toRateLimited(): SendDirectMessageError.RateLimited =
    SendDirectMessageError.RateLimited(
        retryAfterSeconds = get(HttpHeaders.RetryAfter)?.toLongOrNull() ?: UNREAD_RETRY_AFTER_SECONDS,
        scope = if (get(RATE_LIMIT_SCOPE_HEADER) == "recipient") MessageLimitScope.RECIPIENT else MessageLimitScope.SENDER,
    )
