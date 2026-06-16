package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import kotlinx.coroutines.flow.Flow

interface GroupBalanceCallBacks {
    suspend fun getNetDebtsPerUser(groupUuid: String, groupMembers: List<GroupMember>): Map<String, String>

    fun getAllMembers(groupUuid: String): Flow<List<GroupMemberWithFullName>>
}