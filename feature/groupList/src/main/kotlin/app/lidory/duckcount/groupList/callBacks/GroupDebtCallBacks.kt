package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import kotlinx.coroutines.flow.Flow

interface GroupDebtCallBacks {
    suspend fun getGroupTransactions(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): List<TransactionWithFullName>

    fun getAllMembers(groupUuid: String): Flow<List<GroupMemberWithFullName>>

    fun getAllGroupAtiveExpenses(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>>

    fun getAuthenticatedUserUuid(): String

    fun getCurrencySymbol(groupUuid: String): String

    suspend fun refundExpense(creditorUuid: String, debtorUuid: String, amount: String, groupUuid: String)
}