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
     * What became of a request addressed to the caller: still pending, taken by the
     * caller's acceptance, or gone otherwise, refused, cleared by its sender accepting the
     * caller's own request, with their account, or accepted and its record acknowledged
     * since or pruned, see [acknowledgeContactRequestAcceptance].
     *
     * For an acceptance whose answer never came back: the request leaves the listing
     * however it goes, so only this says whether the acceptance was what took it. Always
     * a `200`, anyone else's request reading as gone, so a `404` can only come from the
     * proxy in front of the API.
     */
    suspend fun getContactRequestStatus(requestUuid: String): Result<ContactRequestStatusResponse, NetworkError> =
        client.get<ContactRequestStatusResponse>(
            route = DuckCountRoutes.Contact.REQUEST_ACCEPTANCE,
            pathParams = mapOf("request_uuid" to requestUuid),
        )

    /**
     * Lets the server forget the record of the caller's acceptance of a request, settled on
     * this device and never asked about again, see [getContactRequestStatus].
     *
     * Kept, the records would tell how many contacts the caller accepted, and when. Scoped
     * to the caller and idempotent. A record never acknowledged is pruned by the first
     * acceptance the server records once it is past the session lifetime, and an acceptance
     * whose record is gone reads as gone: only one never asked about again is acknowledged.
     */
    suspend fun acknowledgeContactRequestAcceptance(requestUuid: String): Result<Unit, NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Contact.REQUEST_ACCEPTANCE,
            pathParams = mapOf("request_uuid" to requestUuid),
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
