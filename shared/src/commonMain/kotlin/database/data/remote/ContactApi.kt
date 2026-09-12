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
}
