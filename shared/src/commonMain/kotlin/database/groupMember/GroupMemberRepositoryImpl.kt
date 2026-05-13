package database.groupMember

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

}