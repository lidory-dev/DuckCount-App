import kotlinx.coroutines.flow.Flow
import model.Group

interface GroupListCallBacks {
    suspend fun getAllGroups(): Flow<List<Group>>
}