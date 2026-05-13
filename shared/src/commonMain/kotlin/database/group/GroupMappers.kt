package database.group

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