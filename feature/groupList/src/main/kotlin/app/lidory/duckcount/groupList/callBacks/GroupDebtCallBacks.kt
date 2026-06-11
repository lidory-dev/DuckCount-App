package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import kotlinx.coroutines.flow.Flow

interface GroupDebtCallBacks {
    suspend fun getGroupTransactions(groupUuid: String, groupMembers: List<GroupMember>): List<TransactionWithFullName>

    fun getAllMembers(groupUuid: String): Flow<List<GroupMemberWithFullName>>

    fun getAuthenticatedUserUuid(): String
}