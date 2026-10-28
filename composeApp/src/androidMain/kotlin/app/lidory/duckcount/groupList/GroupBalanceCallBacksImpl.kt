package app.lidory.duckcount.groupList

import app.lidory.duckcount.calculateNetDebtsPerUser
import app.lidory.duckcount.groupList.callBacks.GroupBalanceCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import database.data.repository.ExpenseRepository
import database.data.repository.GroupMemberRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class GroupBalanceCallBacksImpl(
    private val groupMemberRepository: GroupMemberRepository,
    private val userRepository: UserRepository,
    private val expenseRepository: ExpenseRepository,
    private val groupRepository: GroupRepository,
) : GroupBalanceCallBacks {

    override suspend fun getNetDebtsPerUser(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): Map<String, String> {
        return calculateNetDebtsPerUser(
            currency = groupRepository.get(groupUuid).first().currency,
            members = groupMembers.map { groupMember ->
                groupMember.toDb()
            },
            expenses = groupExpenses.map { it.toDb() },
        )
    }

    override fun getAllGroupExpense(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAllFromGroupWithoutDeletedOnes(groupUuid = groupUuid)
            .map { expenses -> expenses.map { it.toGroupListModel() } }
    }

    override suspend fun getCurrencySymbol(groupUuid: String): String {
        return groupRepository.get(groupUuid).first().currency.symbol
    }

    override fun getDisplayName(userUuid: String): Flow<String?> {
        return userRepository.getDisplayName(userUuid)
    }

    override fun getAllMembers(groupUuid: String): Flow<List<GroupMember>> {
        return groupMemberRepository.getMembersFromGroup(groupUuid = groupUuid).map { groupMembers ->
            groupMembers.map { it.toGroupListModel() }
        }
    }
}
