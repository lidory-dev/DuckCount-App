package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.Transaction
import kotlinx.coroutines.flow.Flow

interface GroupSettleUpCallBacks {
    suspend fun getGroupTransactions(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): List<Transaction>

    fun getAllMembers(groupUuid: String): Flow<List<GroupMember>>

    fun getAllGroupActiveExpenses(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAuthenticatedUserUuid(): String

    /** Emits whether the creator removed the user from the group [groupUuid], which leaves it read-only. */
    fun isGroupLeft(groupUuid: String): Flow<Boolean>

    suspend fun getCurrencySymbol(groupUuid: String): String

    suspend fun validateSettlement(creditorUuid: String, debtorUuid: String, amount: String, groupUuid: String)

    /**
     * Emits the name to show for [userUuid] as it changes, or null while this device has none for
     * them, see [GroupSettingsCallBacks.getDisplayName]: a former member of the group included,
     * whose name stays.
     */
    fun getDisplayName(userUuid: String): Flow<String?>
}
