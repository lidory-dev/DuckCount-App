package database.data.mappers

import contact.ContactRelation
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.ContactStanding
import database.data.local.entity.OpenedContactRequest
import database.data.remote.ContactRequestDto
import database.domain.ContactRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

/** The closest of the relations what the tables say holds, see [ContactRelation]. */
fun ContactStanding.toContactRelation(): ContactRelation = when {
    isContact -> ContactRelation.CONTACT
    hasRequestFrom -> ContactRelation.REQUEST_RECEIVED
    hasRequestTo -> ContactRelation.REQUEST_SENT
    else -> ContactRelation.NONE
}

/**
 * The relation each standing comes to, each told once: the query is read again on every write to
 * any of its tables, most of which leave the relation as it was.
 */
fun Flow<ContactStanding>.toContactRelations(): Flow<ContactRelation> =
    map { it.toContactRelation() }.distinctUntilChanged()
