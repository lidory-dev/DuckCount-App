package database.groupMember

interface GroupMemberRepository {
    suspend fun insert(groupMemberEntity: GroupMemberEntity)

    suspend fun update(groupMemberEntity: GroupMemberEntity)

    suspend fun delete(groupMemberEntity: GroupMemberEntity)

}