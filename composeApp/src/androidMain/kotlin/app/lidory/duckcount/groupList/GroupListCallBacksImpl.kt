package app.lidory.duckcount.groupList

import app.lidory.duckcount.GroupLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.GroupLimits.MAX_TITLE_LENGTH
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import database.domain.Group as DBGroup
import database.domain.GroupMember as DBGroupMember
import database.domain.GroupWithMembers as DBGroupWithMembers

class GroupListCallBacksImpl(
    val groupRepository: GroupRepository,
    val userRepository: UserRepository,
) : GroupListCallBacks {

    override val maxTitleLength: Int = MAX_TITLE_LENGTH

    override val maxDescriptionLength: Int = MAX_DESCRIPTION_LENGTH

    override fun getAllGroups(): Flow<List<Group>> {
        return groupRepository.getAll()
            .map { groups ->
                groups.map { group ->
                    group.toGroupListModel()
                }
            }
    }

    override fun getGroup(uuid: String): Flow<Group> {
        return groupRepository.get(uuid)
            .map { group ->
                group.toGroupListModel()
            }
    }

    override fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers> {
        return groupRepository.getWithMembers(uuid).map { groupWithMembers ->
            groupWithMembers.toGroupListModel()
        }
    }

    override fun getUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter)
            .map { users ->
                users.map { user ->
                    user.toGroupListModel()
                }
            }
    }

    override fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userRepository.getAllFromGroupFiltered(filter, groupUuid)
            .map { users ->
                users.map { user ->
                    user.toGroupListModel()
                }
            }
    }

    override fun getUserFullName(uuid: String): Flow<String?> {
        return userRepository.getFullName(uuid)
    }

    override suspend fun createGroup(
        title: String,
        description: String,
        currency: Currency,
        members: List<GroupMember>,
    ) {
        val newGroup = DBGroup(
            title = title,
            description = description,
            currency = currency.toDb(),
        )
        groupRepository.insertWithMembers(
            groupWithMembers = DBGroupWithMembers(
                uuid = newGroup.uuid,
                title = newGroup.title,
                description = newGroup.description,
                currency = newGroup.currency,
                groupMembers = members.map { member ->
                    member.copy(
                        groupUuid = newGroup.uuid,
                    ).toDb()
                },
            ),
        )
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override suspend fun updateGroup(groupWithMembers: GroupWithMembers) {
        groupRepository.updateWithMembers(
            groupWithMembers = DBGroupWithMembers(
                uuid = groupWithMembers.uuid,
                title = groupWithMembers.title,
                description = groupWithMembers.description,
                currency = groupWithMembers.currency.toDb(),
                groupMembers = groupWithMembers.members.map { member ->
                    member.copy(
                        groupUuid = groupWithMembers.uuid,
                    ).toDb()
                },
            ),
        )
    }
}
