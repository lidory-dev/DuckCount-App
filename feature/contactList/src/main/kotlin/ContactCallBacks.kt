import kotlinx.coroutines.flow.Flow
import model.User

interface ContactCallBacks {
    suspend fun getFilteredUsers(filter: String): Flow<List<User>>

    suspend fun getUser(uuid: String): Flow<User>

    fun getAuthenticatedUserUuid(): String?

    suspend fun removeContact(userUuid: String)
}
