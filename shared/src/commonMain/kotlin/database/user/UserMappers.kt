package database.user

fun User.toUserEntity() = UserEntity(
    uuid = uuid,
    username = username,
    fullName = fullName,
)


fun UserEntity.toUser() = User(
    uuid = uuid,
    username = username,
    fullName = fullName,
)
