package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.User
import database.group.GroupRepository
import database.groupMember.GroupMemberRepository
import database.groupMember.toGroupMemberEntity
import database.user.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import database.group.Group as DBGroup
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

    override suspend fun getUserFullName(uuid: String): Flow<String> {
        return userRepository.getFullName(uuid)
    }

    override suspend fun createGroup(title: String, description: String, members: List<GroupMember>) {
        val newGroup = DBGroup(
            title = title,
            description = description,
        )
        groupRepository.insert(
            newGroup,
        )
        members.onEach { member ->
            groupMemberRepository.insert(
                DBGroupMember(
                    uuid = member.uuid,
                    userUuid = member.userUuid,
                    groupUuid = newGroup.uuid,
                    isAdmin = member.isAdmin,
                ).toGroupMemberEntity(),
            )
        }
    }
}