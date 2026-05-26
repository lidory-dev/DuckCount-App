package database.group

import database.groupMember.GroupMember
import database.groupMember.toGroupMemberEntity
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
}