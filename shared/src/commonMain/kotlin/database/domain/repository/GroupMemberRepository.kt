package database.domain.repository

import database.data.local.entity.GroupMemberEntity
import database.domain.GroupMember
import kotlinx.coroutines.flow.Flow

interface GroupMemberRepository {
    suspend fun insert(groupMemberEntity: GroupMemberEntity)

    suspend fun update(groupMemberEntity: GroupMemberEntity)

    suspend fun delete(groupMemberEntity: GroupMemberEntity)

    fun getMembersFromGroup(groupUuid: String): Flow<List<GroupMember>>

}