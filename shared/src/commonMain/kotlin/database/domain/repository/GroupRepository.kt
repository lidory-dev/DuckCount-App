package database.domain.repository

import database.domain.Group
import database.domain.GroupMember
import database.domain.GroupWithMembers
import kotlinx.coroutines.flow.Flow

interface GroupRepository {

    suspend fun insert(group: Group)

    suspend fun insertMembers(groupMembers: List<GroupMember>)

    suspend fun insertWithMembers(groupWithMembers: GroupWithMembers)

    suspend fun update(group: Group)

    suspend fun updateWithMembers(groupWithMembers: GroupWithMembers)

    suspend fun removeGroupMember(userUuid: String, groupUuid: String)

    suspend fun delete(group: Group)

    suspend fun getAll(): Flow<List<Group>>

    suspend fun get(uuid: String): Flow<Group>

    suspend fun getWithMembers(uuid: String): Flow<GroupWithMembers>
}