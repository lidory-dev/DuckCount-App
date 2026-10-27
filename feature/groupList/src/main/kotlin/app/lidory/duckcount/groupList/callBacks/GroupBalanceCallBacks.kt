package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import kotlinx.coroutines.flow.Flow

interface GroupBalanceCallBacks {
    suspend fun getNetDebtsPerUser(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): Map<String, String>

    fun getAllGroupExpense(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllMembers(groupUuid: String): Flow<List<GroupMemberWithFullName>>

    suspend fun getCurrencySymbol(groupUuid: String): String

    /** The name of [userUuid], or null for somebody this device has no name for. */
    suspend fun getFullName(userUuid: String): String?
}
