package database.data.local.entity

/**
 * What the tables say of one user, as far as their being the user's contact goes, read in one
 * query, see [ContactRequestDao.getStandingAsFlow][database.data.local.dao.ContactRequestDao.getStandingAsFlow]:
 * whether they are a contact, removed ones aside, see [ContactEntity.isRemoved], whether a request
 * of theirs waits for the user's answer, see [ContactRequestEntity], and whether one of the user's
 * the server is known to hold waits for theirs, see [OutgoingContactRequestEntity.isStored].
 */
data class ContactStanding(
    val isContact: Boolean,
    val hasRequestFrom: Boolean,
    val hasRequestTo: Boolean,
)
