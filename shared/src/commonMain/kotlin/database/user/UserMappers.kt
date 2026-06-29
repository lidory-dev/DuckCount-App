package database.user

fun User.toUserEntity() = UserEntity(
    uuid = uuid,
    username = username,
    fullName = fullName,
    isDeleted = isDeleted,
)


fun UserEntity.toUser() = User(
    uuid = uuid,
    username = username,
    fullName = fullName,
    isDeleted = isDeleted,
)
