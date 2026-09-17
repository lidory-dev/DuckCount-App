package database.data.remote

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/**
 * The most message UUIDs one acknowledgement may carry. The server refuses a larger batch
 * with a `400`.
 */
const val MAX_ACKNOWLEDGEMENT_BATCH = 100

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
     * Rate-limited per sender, so a `429` can come back.
     */
    suspend fun sendDirectMessage(body: SendDirectMessageBody): Result<Unit, NetworkError> =
        client.post<Unit, SendDirectMessageBody>(
            route = DuckCountRoutes.Message.MESSAGES,
            body = body,
        )

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
