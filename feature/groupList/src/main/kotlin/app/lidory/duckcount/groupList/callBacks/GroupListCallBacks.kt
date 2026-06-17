package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.flow.Flow

interface GroupListCallBacks {
    suspend fun getAllGroups(): Flow<List<Group>>

    suspend fun getGroup(uuid: String): Flow<Group>

    suspend fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers>

    suspend fun getUsersFiltered(filter: String): Flow<List<User>>

    suspend fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>>

    suspend fun getUserFullName(uuid: String): Flow<String?>

    suspend fun createGroup(title: String, description: String, members: List<GroupMember>)

    fun getAuthenticatedUserUuid(): String?
}