package contact

import database.data.local.dao.ContactDao
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.UserDao

/**
 * Somebody this device did not know about before a sync ran.
 *
 * [fullName] is null while their profile has not been read, which a sync attempts
 * but a failed profile read leaves undone.
 */
data class ContactArrival(
    val userUuid: String,
    val fullName: String?,
    val kind: Kind,
) {
    enum class Kind {
        /** They are asking to become a contact. */
        REQUEST,

        /** They accepted the user's request, and are now a contact. */
        CONTACT,
    }
}

/** Who this device knew about at one moment, to compare a later one against. */
class ContactSnapshot internal constructor(
    internal val requestSenders: Set<String>,

    /** The groups of the requests the user had out, by recipient, see [ContactArrivals.since]. */
    internal val outgoingGroups: Map<String, List<ByteArray>>,

    internal val contacts: Set<String>,
)

/**
 * Tells who turned up while a sync was running.
 *
 * What a push signal carries is that *something* arrived, never what or from whom:
 * the server is blind to the message the signal is about. So the only way to say
 * anything useful to the user is to look at what the sync left behind, which is what
 * this compares.
 */
class ContactArrivals(
    private val contactDao: ContactDao,
    private val contactRequestDao: ContactRequestDao,
    private val directChannelDao: DirectChannelDao,
    private val userDao: UserDao,
) {
    /** Who this device knows about right now. */
    suspend fun snapshot(): ContactSnapshot = ContactSnapshot(
        requestSenders = contactRequestDao.getOpenedOnce().mapTo(HashSet()) { it.senderUserUuid },
        outgoingGroups = contactRequestDao.getOutgoing().groupBy(
            keySelector = { it.recipientUserUuid },
            valueTransform = { it.mlsGroupId },
        ),
        contacts = contactDao.getUserUuids().toHashSet(),
    )

    /**
     * Who [snapshot] knew as a contact and this device no longer does.
     *
     * A contact who removed the user, in practice: their client sends the notice and
     * the sync acts on it, which makes anything still being said about them on screen
     * untrue.
     */
    suspend fun departures(snapshot: ContactSnapshot): List<String> {
        val contacts = contactDao.getUserUuids().toHashSet()

        return snapshot.contacts.filterNot { it in contacts }
    }

    /**
     * Who [snapshot] had not seen, with the name to show for each.
     *
     * A request whose sender became a contact in the same sync counts once, as the
     * contact: they accepted before the user got round to answering, and telling them
     * about a request they no longer have would be wrong.
     *
     * A new contact is only told about when they answered a request the user sent, which
     * is what the announcement says. Their answer is read in the group that request created,
     * which becomes the channel to them, see
     * [ContactRequestRepository.receiveAnswer][database.data.repository.ContactRequestRepository.receiveAnswer],
     * or one kept behind it once Welcomes read later in the same sync replaced it. Anybody else
     * is no news to the user: the user's own acceptance of a request of theirs leaves the
     * channel in the group *that* request created, however late it landed, even when the two
     * had asked each other, and a contact the vault gave back is not one the user has a
     * request out to.
     */
    suspend fun since(snapshot: ContactSnapshot): List<ContactArrival> {
        val contacts = contactDao.getUserUuids()

        val newContacts = contacts
            .filterNot { it in snapshot.contacts }
            .filter { userUuid ->
                val channel = directChannelDao.get(peerUuid = userUuid)

                snapshot.outgoingGroups[userUuid].orEmpty().any {
                    it contentEquals channel?.mlsGroupId ||
                        it contentEquals channel?.previousMlsGroupId ||
                        it contentEquals channel?.olderMlsGroupId
                }
            }
            .map {
                ContactArrival(
                    userUuid = it,
                    fullName = userDao.getFullName(uuid = it),
                    kind = ContactArrival.Kind.CONTACT,
                )
            }

        val newRequests = contactRequestDao.getOpenedOnce()
            .filterNot { it.senderUserUuid in snapshot.requestSenders }
            .filterNot { it.senderUserUuid in contacts }
            .map {
                ContactArrival(
                    userUuid = it.senderUserUuid,
                    fullName = it.senderFullName,
                    kind = ContactArrival.Kind.REQUEST,
                )
            }

        return newRequests + newContacts
    }
}
