package database.group

import database.groupMember.toGroupMember
import database.groupMember.toGroupMemberEntity

fun Group.toGroupEntity() = GroupEntity(
    uuid = uuid,
    description = description,
    title = title,
)

fun GroupEntity.toGroup() = Group(
    uuid = uuid,
    description = description,
    title = title,
)

fun GroupWithMembers.toGroupWithMembersEntity() = GroupWithMembersEntity(
    group = Group(
        uuid = uuid,
        title = title,
        description = description,
    ).toGroupEntity(),
    groupMembers = groupMembers.map { it.toGroupMemberEntity() },
)

fun GroupWithMembersEntity.toGroupWithMembers() = GroupWithMembers(
    uuid = group.uuid,
    title = group.title,
    description = group.description,
    groupMembers = groupMembers.map { it.toGroupMember() },
)
