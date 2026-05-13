package database.group

import kotlinx.coroutines.flow.Flow

interface GroupRepository {

    suspend fun insert(group: Group)

    suspend fun update(group: Group)

    suspend fun delete(group: Group)

    suspend fun getAll(): Flow<List<Group>>
}