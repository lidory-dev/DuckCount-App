package database.groupMember

import kotlinx.coroutines.flow.Flow

interface GroupMemberRepository {
    suspend fun insert(groupMemberEntity: GroupMemberEntity)

    suspend fun update(groupMemberEntity: GroupMemberEntity)

    suspend fun delete(groupMemberEntity: GroupMemberEntity)

    fun getMembersFromGroup(groupUuid: String): Flow<List<GroupMember>>

}