import kotlinx.coroutines.flow.Flow
import model.User
import viewmodel.ContactListState

interface ContactCallBacks {
    suspend fun getFilteredUsers(filter: String): Flow<ContactListState>

    suspend fun getUser(uuid: String): Flow<User>

}