import kotlinx.coroutines.flow.Flow
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
}
