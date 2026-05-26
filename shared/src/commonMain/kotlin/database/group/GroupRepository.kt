package database.group

import database.groupMember.GroupMember
import kotlinx.coroutines.flow.Flow

interface GroupRepository {

    suspend fun insert(group: Group)

    suspend fun insertMembers(groupMembers: List<GroupMember>)

    suspend fun insertWithMembers(groupWithMembers: GroupWithMembers)

    suspend fun update(group: Group)

    suspend fun delete(group: Group)

    suspend fun getAll(): Flow<List<Group>>

    suspend fun get(uuid: String): Flow<Group>
}