package database.data.mappers

import database.data.local.entity.GroupMemberEntity
import database.data.remote.GroupMemberDto
import database.domain.GroupMember

fun GroupMemberDto.toGroupMemberEntity(groupUuid: String) = GroupMemberEntity(
    userUuid = user_uuid,
    groupUuid = groupUuid,
    isAdmin = is_admin,
)

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