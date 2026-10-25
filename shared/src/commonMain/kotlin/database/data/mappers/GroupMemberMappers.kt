package database.data.mappers

import database.data.local.entity.GroupMemberEntity
import database.domain.GroupMember

fun GroupMember.toGroupMemberEntity() = GroupMemberEntity(
    userUuid = userUuid,
    groupUuid = groupUuid,
    isAdmin = isAdmin,
)

fun GroupMemberEntity.toGroupMember() = GroupMember(
    userUuid = userUuid,
    groupUuid = groupUuid,
    isAdmin = isAdmin,
)
