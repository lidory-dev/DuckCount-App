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
import database.domain.repository.GroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GroupRepositoryImpl(
    private val groupDao: GroupDao,
) : GroupRepository {
    override suspend fun insert(group: Group) {
        groupDao.insert(groupEntity = group.toGroupEntity())
    }

    override suspend fun insertMembers(groupMembers: List<GroupMember>) {
        groupDao.insertMembers(groupMemberEntities = groupMembers.map { it.toGroupMemberEntity() })
    }

    override suspend fun insertWithMembers(groupWithMembers: GroupWithMembers) {
        groupDao.insertWithMembers(groupWithMembers.toGroupWithMembersEntity())
    }

    override suspend fun update(group: Group) {
        groupDao.update(groupEntity = group.toGroupEntity())
    }

    override suspend fun updateWithMembers(groupWithMembers: GroupWithMembers) {
        groupDao.updateWithMembers(groupWithMembers.toGroupWithMembersEntity())
    }

    override suspend fun removeGroupMember(userUuid: String, groupUuid: String) {
        groupDao.removeGroupMember(userUuid, groupUuid)
    }

    override suspend fun delete(group: Group) {
        groupDao.delete(groupEntity = group.toGroupEntity())
    }

    override suspend fun getAll(): Flow<List<Group>> {
        return groupDao.getAllAsFlow().map { groupEntities ->
            groupEntities.map { it.toGroup() }
        }
    }

    override suspend fun get(uuid: String): Flow<Group> {
        return groupDao.getAsFlow(uuid).map { groupEntity ->
            groupEntity.toGroup()
        }
    }

    override suspend fun getWithMembers(uuid: String): Flow<GroupWithMembers> {
        return groupDao.getWithMembers(uuid).map { groupWithMembersEntity ->
            groupWithMembersEntity.toGroupWithMembers()
        }
    }
}