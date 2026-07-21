package database.data.repository

import database.data.local.dao.GroupMemberDao
import database.data.local.entity.GroupMemberEntity
import database.data.mappers.toGroupMember
import database.domain.GroupMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GroupMemberRepository(
    private val groupMemberDao: GroupMemberDao,
) {
    suspend fun insert(groupMemberEntity: GroupMemberEntity) {
        groupMemberDao.insert(groupMemberEntity)
    }

    suspend fun update(groupMemberEntity: GroupMemberEntity) {
        groupMemberDao.update(groupMemberEntity)
    }

    suspend fun delete(groupMemberEntity: GroupMemberEntity) {
        groupMemberDao.delete(groupMemberEntity)
    }

    fun getMembersFromGroup(groupUuid: String): Flow<List<GroupMember>> {
        return groupMemberDao.getMembersFromGroup(groupUuid).map { groupMemberEntities ->
            groupMemberEntities.map { it.toGroupMember() }
        }
    }
}
