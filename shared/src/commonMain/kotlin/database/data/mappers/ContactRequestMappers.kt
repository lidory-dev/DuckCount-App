package database.data.mappers

import database.data.local.entity.ContactRequestEntity
import database.data.remote.ContactRequestDto

fun ContactRequestDto.toContactRequestEntity() = ContactRequestEntity(
    uuid = uuid,
    senderUserUuid = senderUserUuid,
    ciphertext = ciphertext,
    createdAt = createdAt,
)
