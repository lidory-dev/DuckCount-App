package database.groupMember

fun GroupMember.toGroupMemberEntity() = GroupMemberEntity(
    uuid = uuid,
    userUuid = userUuid,
    groupUuid = groupUuid,
    isAdmin = isAdmin,
)

fun GroupMemberEntity.toGroupMember() = GroupMember(
    uuid = uuid,
    userUuid = userUuid,
    groupUuid = groupUuid,
    isAdmin = isAdmin,
)