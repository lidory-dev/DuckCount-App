package database.data.mappers

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupWithMembersEntity
import database.data.remote.GroupDto
import database.domain.Group
import database.domain.GroupWithMembers

fun GroupDto.toGroupWithMembersEntity() = GroupWithMembersEntity(
    group = Group(
        uuid = uuid,
        title = fakeTitle(),
        description = fakeDescription(),
        currency = fakeCurrency(),
    ).toGroupEntity(),
    groupMembers = members.map {
        it.toGroupMemberEntity(groupUuid = uuid)
    },
)

fun Group.toGroupEntity() = GroupEntity(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency,
)

fun GroupEntity.toGroup() = Group(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency,
)

fun GroupWithMembers.toGroupWithMembersEntity() = GroupWithMembersEntity(
    group = Group(
        uuid = uuid,
        title = title,
        description = description,
        currency = currency,
    ).toGroupEntity(),
    groupMembers = groupMembers.map { it.toGroupMemberEntity() },
)

fun GroupWithMembersEntity.toGroupWithMembers() = GroupWithMembers(
    uuid = group.uuid,
    title = group.title,
    description = group.description,
    currency = group.currency,
    groupMembers = groupMembers.map { it.toGroupMember() },
)
