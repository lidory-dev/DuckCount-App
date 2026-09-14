import kotlinx.coroutines.flow.Flow
import model.AddContactError
import model.User

interface ContactCallBacks {
    suspend fun getFilteredUsers(filter: String): Flow<List<User>>

    suspend fun getUser(uuid: String): Flow<User>

    fun getAuthenticatedUserUuid(): String?

    suspend fun removeContact(userUuid: String)

    /**
     * Emits the username the user reserved to be discoverable by, or null when they
     * have not reserved one yet.
     *
     * Reserving one is the settings' business, this is only used to tell the user the
     * contact list is waiting on it.
     */
    fun getReservedUsername(): Flow<String?>

    /** How many contact requests are waiting for the user, straight off the local cache. */
    fun getContactRequestCount(): Flow<Int>

    /**
     * Pulls the requests addressed to the user into the local cache.
     *
     * Failing is not reported: the user keeps seeing the count they already had, which
     * is what caching it is for.
     */
    suspend fun refreshContactRequests()

    /**
     * Sends a contact request to whoever reserved the discovery [username].
     *
     * Returns null once the request is sent, or why it could not be.
     */
    suspend fun sendContactRequest(username: String): AddContactError?
}
