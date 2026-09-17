package database.data.mappers

import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.OpenedContactRequest
import database.data.remote.ContactRequestDto
import database.domain.ContactRequest

fun ContactRequestDto.toContactRequestEntity() = ContactRequestEntity(
    uuid = uuid,
    senderUserUuid = senderUserUuid,
    ciphertext = ciphertext,
    createdAt = createdAt,
)

fun OpenedContactRequest.toContactRequest() = ContactRequest(
    uuid = uuid,
    senderUserUuid = senderUserUuid,
    senderFullName = senderFullName,
)
