import kotlinx.coroutines.flow.Flow
import viewmodel.ContactListState

interface ContactCallBacks {
    suspend fun getFilteredUsers(filter: String): Flow<ContactListState>

}