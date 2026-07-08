package database.data.mappers

import database.data.local.entity.UserEntity
import database.domain.User

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
