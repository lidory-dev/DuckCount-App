package database.data.remote

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/**
 * Remote data source for the caller's contacts.
 */
class ContactApi(
    private val client: DuckCountClient,
) {
    /**
     * Lists the contact requests addressed to the caller, oldest first.
     *
     * Incoming only: requests the caller has sent are not returned, so the count this
     * feeds is always "waiting on you".
     */
    suspend fun getContactRequests(): Result<ListContactRequestsResponse, NetworkError> =
        client.get<ListContactRequestsResponse>(
            route = DuckCountRoutes.Contact.REQUESTS,
        )

    /**
     * Resolves [username], a discovery username digest, to its holder and one of their
     * key packages.
     *
     * A `GET` that mutates: it consumes a one-time key package whether or not a request
     * follows. A username nobody reserved and a user with no key package are both a
     * `404`. Rate-limited per caller, failed lookups included, so a `429` can come back.
     */
    suspend fun lookupContact(username: String): Result<ContactLookupResponse, NetworkError> =
        client.get<ContactLookupResponse>(
            route = DuckCountRoutes.Contact.LOOKUP,
            pathParams = mapOf("username" to username),
        )

    /**
     * Sends a contact request.
     *
     * A request to this recipient already pending is a `409`, a recipient that does not
     * exist a `404`, and the route is rate-limited like the lookup.
     */
    suspend fun sendContactRequest(body: SendContactRequestBody): Result<Unit, NetworkError> =
        client.post<Unit, SendContactRequestBody>(
            route = DuckCountRoutes.Contact.REQUESTS,
            body = body,
        )

    /**
     * Accepts a request addressed to the caller, which the server delivers to its sender as
     * a direct message.
     *
     * Accepting consumes the request, and clears the reverse one too if the sender had a
     * request from the caller pending. A request that is not, or no longer, waiting for
     * the caller is a `404`.
     */
    suspend fun acceptContactRequest(
        requestUuid: String,
        body: AcceptContactRequestBody,
    ): Result<Unit, NetworkError> =
        client.post<Unit, AcceptContactRequestBody>(
            route = DuckCountRoutes.Contact.REQUEST_ACCEPTANCE,
            pathParams = mapOf("request_uuid" to requestUuid),
            body = body,
        )

    /**
     * Refuses a request addressed to the caller, deleting it. The sender is not told, and
     * may send another one.
     *
     * A request that is not, or no longer, waiting for the caller is a `404`.
     */
    suspend fun refuseContactRequest(requestUuid: String): Result<Unit, NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Contact.REQUEST,
            pathParams = mapOf("request_uuid" to requestUuid),
        )
}
