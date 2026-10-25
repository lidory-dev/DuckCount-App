package database.data.mappers

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupWithMembersEntity
import database.domain.Group
import database.domain.GroupWithMembers

fun GroupEntity.toGroup() = Group(
    uuid = uuid,
    description = description,
    title = title,
    currency = currency,
)

fun GroupWithMembersEntity.toGroupWithMembers() = GroupWithMembers(
    uuid = group.uuid,
    title = group.title,
    description = group.description,
    currency = group.currency,
    groupMembers = groupMembers.map { it.toGroupMember() },
)
