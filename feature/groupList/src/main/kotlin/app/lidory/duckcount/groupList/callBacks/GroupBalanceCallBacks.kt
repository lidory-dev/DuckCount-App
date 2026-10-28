package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import kotlinx.coroutines.flow.Flow

interface GroupBalanceCallBacks {
    suspend fun getNetDebtsPerUser(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): Map<String, String>

    fun getAllGroupExpense(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAllMembers(groupUuid: String): Flow<List<GroupMember>>

    suspend fun getCurrencySymbol(groupUuid: String): String

    /**
     * Emits the name to show for [userUuid] as it changes, or null while this device has none for
     * them, see [GroupSettingsCallBacks.getDisplayName]: a former member of the group included,
     * whose name stays.
     */
    fun getDisplayName(userUuid: String): Flow<String?>
}
