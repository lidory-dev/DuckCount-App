package database.data.repository

import database.data.local.dao.GroupDao
import database.data.mappers.toGroup
import database.data.mappers.toGroupEntity
import database.data.mappers.toGroupMemberEntity
import database.data.mappers.toGroupWithMembers
import database.data.mappers.toGroupWithMembersEntity
import database.domain.Group
import database.domain.GroupMember
import database.domain.GroupWithMembers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class GroupRepository(
    private val groupDao: GroupDao,
) {
    suspend fun insert(group: Group) {
        groupDao.insert(groupEntity = group.toGroupEntity())
    }

    suspend fun insertMembers(groupMembers: List<GroupMember>) {
        groupDao.insertMembers(groupMemberEntities = groupMembers.map { it.toGroupMemberEntity() })
    }

    suspend fun insertWithMembers(groupWithMembers: GroupWithMembers) {
        groupDao.insertWithMembers(groupWithMembers.toGroupWithMembersEntity())
    }

    suspend fun update(group: Group) {
        groupDao.update(groupEntity = group.toGroupEntity())
    }

    suspend fun updateWithMembers(groupWithMembers: GroupWithMembers) {
        groupDao.updateWithMembers(groupWithMembers.toGroupWithMembersEntity())
    }

    suspend fun removeGroupMember(userUuid: String, groupUuid: String) {
        groupDao.removeGroupMember(userUuid, groupUuid)
    }

    suspend fun delete(group: Group) {
        groupDao.delete(groupEntity = group.toGroupEntity())
    }

    fun getAll(): Flow<List<Group>> {
        return groupDao.getAllAsFlow().map { groupEntities ->
            groupEntities.map { it.toGroup() }
        }
    }

    fun get(uuid: String): Flow<Group> {
        return groupDao.getAsFlow(uuid).filterNotNull().map { groupEntity ->
            groupEntity.toGroup()
        }
    }

    fun getWithMembers(uuid: String): Flow<GroupWithMembers> {
        return groupDao.getWithMembers(uuid).filterNotNull().map { groupWithMembersEntity ->
            groupWithMembersEntity.toGroupWithMembers()
        }
    }
}
