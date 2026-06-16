package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.callBacks.GroupBalanceCallBacks
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import database.expense.ExpenseRepository
import database.groupMember.GroupMemberRepository
import database.user.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import database.groupMember.GroupMember as DBGroupMember

class GroupBalanceCallBacksImpl(
    private val groupMemberRepository: GroupMemberRepository,
    private val userRepository: UserRepository,
    private val expenseRepository: ExpenseRepository,
) : GroupBalanceCallBacks {

    override suspend fun getGroupDebt(groupUuid: String, groupMembers: List<GroupMember>): Map<String, String> {
        return getDeptFromGroup(
            members = groupMembers.map { groupMember ->
                DBGroupMember(
                    uuid = groupMember.uuid,
                    userUuid = groupMember.userUuid,
                    groupUuid = groupMember.groupUuid,
                    isAdmin = groupMember.isAdmin,
                )
            },
            expenses = expenseRepository.getAllFromGroup(groupUuid = groupUuid).first(),
        )
    }

    override fun getAllMembers(groupUuid: String): Flow<List<GroupMemberWithFullName>> {
        return groupMemberRepository.getMembersFromGroup(groupUuid = groupUuid).map { groupMembers ->
            groupMembers.map { groupMember ->
                GroupMemberWithFullName(
                    member = GroupMember(
                        uuid = groupMember.uuid,
                        userUuid = groupMember.userUuid,
                        groupUuid = groupMember.groupUuid,
                        isAdmin = groupMember.isAdmin,
                    ),
                    // TODO: Show that the username was not found?
                    fullName = userRepository.getFullName(groupMember.userUuid).first() ?: groupMember.userUuid,
                )
            }
        }
    }
}
