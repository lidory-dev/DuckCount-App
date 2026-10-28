package contact

/**
 * Where the user stands with somebody, as far as the two of them being contacts goes, see
 * [ContactRequestRepository.relationTo][database.data.repository.ContactRequestRepository.relationTo].
 *
 * Each holds only when none listed before it does: a request either way stops counting once they
 * are contacts, and one from them is what the user can act on, whatever request the user has out
 * to them, accepting it being the way the two requests end.
 */
enum class ContactRelation {
    /**
     * One of the user's contacts, blocked ones included, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity]: they still have
     * a profile to show.
     */
    CONTACT,

    /**
     * They sent the user a request, which waits for the user's answer where the contact requests
     * are listed: one this device has opened, see
     * [ContactRequestDao.getOpenedAsFlow][database.data.local.dao.ContactRequestDao.getOpenedAsFlow],
     * the user's acceptance of it gone out without its answer coming back included, which
     * accepting again completes.
     */
    REQUEST_RECEIVED,

    /**
     * The user sent them a request the server is known to hold, which this device still records,
     * see [OutgoingContactRequestEntity.isStored][database.data.local.entity.OutgoingContactRequestEntity.isStored]:
     * it goes once they answer it, never when they refuse it, which nothing tells this device. One
     * whose sending ended in doubt does not count, so that they can be asked again.
     */
    REQUEST_SENT,

    /** None of the above: somebody the user can send a request to. */
    NONE,
}
