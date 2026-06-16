package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import database.group.GroupRepository
import database.groupMember.GroupMemberRepository
import database.user.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import database.group.Group as DBGroup
import database.group.GroupWithMembers as DBGroupWithMember
import database.groupMember.GroupMember as DBGroupMember

class GroupListCallBacksImpl(
    val groupRepository: GroupRepository,
    val groupMemberRepository: GroupMemberRepository,
    val userRepository: UserRepository,
) : GroupListCallBacks {
    override suspend fun getAllGroups(): Flow<List<Group>> {
        return groupRepository.getAll()
            .map { groups ->
                groups.map {
                    Group(
                        uuid = it.uuid,
                        description = it.description,
                        title = it.title,
                    )
                }
            }
    }

    override suspend fun getGroup(uuid: String): Flow<Group> {
        return groupRepository.get(uuid)
            .map { group ->
                Group(
                    uuid = group.uuid,
                    title = group.title,
                    description = group.description,
                )
            }
    }

    override suspend fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers> {
        return groupRepository.getWithMembers(uuid).map { groupWithMembers ->
            GroupWithMembers(
                uuid = groupWithMembers.uuid,
                title = groupWithMembers.title,
                description = groupWithMembers.description,
                members = groupWithMembers.groupMembers.map { groupMember ->
                    GroupMember(
                        uuid = groupMember.uuid,
                        userUuid = groupMember.userUuid,
                        groupUuid = groupMember.groupUuid,
                        isAdmin = groupMember.isAdmin,
                    )
                },
            )
        }
    }

    override suspend fun getUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter)
            .map { users ->
                users.map {
                    User(
                        uuid = it.uuid,
                        username = it.username,
                        fullName = it.fullName,
                    )
                }
            }
    }

    override suspend fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userRepository.getAllFromGroupFiltered(filter, groupUuid)
            .map { users ->
                users.map {
                    User(
                        uuid = it.uuid,
                        username = it.username,
                        fullName = it.fullName,
                    )
                }
            }
    }

    override suspend fun getUserFullName(uuid: String): Flow<String?> {
        return userRepository.getFullName(uuid)
    }

    override suspend fun createGroup(title: String, description: String, members: List<GroupMember>) {
        val newGroup = DBGroup(
            title = title,
            description = description,
        )
        groupRepository.insertWithMembers(
            groupWithMembers = DBGroupWithMember(
                uuid = newGroup.uuid,
                title = newGroup.title,
                description = newGroup.description,
                groupMembers = members.map { member ->
                    DBGroupMember(
                        uuid = member.uuid,
                        userUuid = member.userUuid,
                        groupUuid = newGroup.uuid,
                        isAdmin = member.isAdmin,
                    )
                },
            ),
        )
    }
}
