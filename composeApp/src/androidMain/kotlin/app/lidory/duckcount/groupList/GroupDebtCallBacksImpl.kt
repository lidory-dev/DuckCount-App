package app.lidory.duckcount.groupList

import app.lidory.duckcount.getTransactionFromGroup
import app.lidory.duckcount.groupList.callBacks.GroupDebtCallBacks
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import database.data.repository.ExpenseRepository
import database.data.repository.GroupMemberRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import database.domain.GroupMember as DBGroupMember

class GroupDebtCallBacksImpl(
    private val groupMemberRepository: GroupMemberRepository,
    private val userRepository: UserRepository,
    private val expenseRepository: ExpenseRepository,
) : GroupDebtCallBacks {
    override suspend fun getGroupTransactions(
        groupUuid: String,
        groupMembers: List<GroupMember>,
    ): List<TransactionWithFullName> {
        return getTransactionFromGroup(
            members = groupMembers.map { groupMember ->
                DBGroupMember(
                    userUuid = groupMember.userUuid,
                    groupUuid = groupMember.groupUuid,
                    isAdmin = groupMember.isAdmin,
                )
            },
            expenses = expenseRepository.getAllFromGroup(groupUuid = groupUuid).first(),
        ).map { transaction ->
            TransactionWithFullName(
                debtorUuid = transaction.debtorUuid,
                debtorFullName = userRepository.getFullName(transaction.debtorUuid).first() ?: transaction.debtorUuid,
                creditorUuid = transaction.creditorUuid,
                creditorFullName = userRepository.getFullName(transaction.creditorUuid).first()
                    ?: transaction.creditorUuid,
                amount = transaction.amount,
            )
        }
    }

    override fun getAllMembers(groupUuid: String): Flow<List<GroupMemberWithFullName>> {
        return groupMemberRepository.getMembersFromGroup(groupUuid = groupUuid).map { groupMembers ->
            groupMembers.map { groupMember ->
                GroupMemberWithFullName(
                    member = GroupMember(
                        userUuid = groupMember.userUuid,
                        groupUuid = groupMember.groupUuid,
                        isAdmin = groupMember.isAdmin,
                    ),
                    // TODO: remove the check of isAdmin when the admin user is correctly inserted (see CreateGroupViewModel)
                    fullName = userRepository.getFullName(groupMember.userUuid).first() ?: groupMember.userUuid,
                )
            }
        }
    }

    override fun getAuthenticatedUserUuid(): String {
        return authenticationUtils.getAuthenticatedUserUuid() ?: ""
    }
}