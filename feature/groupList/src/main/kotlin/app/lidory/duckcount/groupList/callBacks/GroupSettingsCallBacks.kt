package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.GroupWithMembers
import kotlinx.coroutines.flow.Flow

interface GroupSettingsCallBacks {
    suspend fun getGroupWithMembers(groupUuid: String): Flow<GroupWithMembers>

    fun getUserFullName(userUuid: String): Flow<String?>

    suspend fun removeGroupMember(userUuid: String, groupUuid: String)
}