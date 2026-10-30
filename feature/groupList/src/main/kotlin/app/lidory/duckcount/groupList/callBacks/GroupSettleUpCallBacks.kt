package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupCreationFailure
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

    /** Emits whether the user is no longer in the group [groupUuid], removed or left, which leaves it read-only. */
    fun isGroupLeft(groupUuid: String): Flow<Boolean>

    suspend fun getCurrencySymbol(groupUuid: String): String

    /**
     * Records that [debtorUuid] paid [amount] back to the user, [creditorUuid], in the group
     * [groupUuid], on the server first, as every expense of a group is written: the user is the one
     * paid back, who alone records it. Returns null once the server took it, which the group then
     * shows, or why it did not, nothing being recorded then.
     */
    suspend fun validateSettlement(
        creditorUuid: String,
        debtorUuid: String,
        amount: String,
        groupUuid: String,
    ): GroupCreationFailure?

    /**
     * Emits the name to show for [userUuid] as it changes, or null while this device has none for
     * them, see [GroupSettingsCallBacks.getDisplayName]: a former member of the group included,
     * whose name stays.
     */
    fun getDisplayName(userUuid: String): Flow<String?>
}
