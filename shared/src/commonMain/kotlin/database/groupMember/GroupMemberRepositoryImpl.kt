package database.groupMember

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GroupMemberRepositoryImpl(
    private val groupMemberDao: GroupMemberDao,
) : GroupMemberRepository {
    override suspend fun insert(groupMemberEntity: GroupMemberEntity) {
        groupMemberDao.insert(groupMemberEntity)
    }

    override suspend fun update(groupMemberEntity: GroupMemberEntity) {
        groupMemberDao.update(groupMemberEntity)
    }

    override suspend fun delete(groupMemberEntity: GroupMemberEntity) {
        groupMemberDao.delete(groupMemberEntity)
    }

    override fun getMembersFromGroup(groupUuid: String): Flow<List<GroupMember>> {
        return groupMemberDao.getMembersFromGroup(groupUuid).map { groupMemberEntities ->
            groupMemberEntities.map { it.toGroupMember() }
        }
    }

}