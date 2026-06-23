package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GroupSettingsCallBacksImpl(
    val groupRepository: GroupRepository,
    val userRepository: UserRepository,
) : GroupSettingsCallBacks {
    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override suspend fun getGroupWithMembers(groupUuid: String): Flow<GroupWithMembers> {
        return groupRepository.getWithMembers(groupUuid).map { groupWithMembers ->
            GroupWithMembers(
                uuid = groupWithMembers.uuid,
                title = groupWithMembers.title,
                description = groupWithMembers.description,
                currency = groupWithMembers.currency.toGroupListModel(),
                members = groupWithMembers.groupMembers.map { groupMember ->
                    GroupMember(
                        userUuid = groupMember.userUuid,
                        groupUuid = groupMember.groupUuid,
                        isAdmin = groupMember.isAdmin,
                    )
                },
            )
        }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override suspend fun removeGroupMember(userUuid: String, groupUuid: String) {
        groupRepository.removeGroupMember(userUuid, groupUuid)
    }
}