package push

import authenticationUtils.SessionLeftovers
import database.data.local.dao.ContactDao
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.GroupMemberDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Who may wake the user: their contacts, blocked ones aside, whoever they sent a contact
 * request still waiting for its answer, and, with an expense of a group alone, whoever shares a
 * group they are still in with them, see [PushSignal.mayWake].
 *
 * The server keeps no record of who is whose contact, so it pushes whatever anybody sends the
 * user, and only the device can tell a contact from a stranger. Every signal names its sender
 * for that, and one from anybody this list does not name is dropped as it arrives, with
 * nothing shown and nothing synced. A stranger can still have the phone woken in the
 * background, never seen or heard. A contact request is the exception, coming from a stranger
 * by definition.
 *
 * Kept apart, see [WakeListStore], since the check runs while the device is locked, when
 * neither the database nor the rest of the vault can be read. This is what keeps it in line with
 * them, rebuilding it whole from what the database holds rather than patching it: after the
 * contacts are synced or brought back after a login, and in the very flow that takes
 * somebody off it, a contact removed by either side, a contact blocked, a request that never
 * reached the server. Somebody who no longer belongs on it must not wake the user a moment
 * longer. Somebody new is added the same way, though missing them would only cost a
 * notification: what they sent waits on the server for the next sync. It is emptied as the
 * session ends, see [SessionLeftovers].
 *
 * Whoever shares a group with the user is kept on it the same way, rebuilt after every group sync
 * and in the flows that change who is in one: the creator's own additions and removals, see
 * `group.GroupMembershipSender`, and the user leaving one, see `group.GroupLeaver`. Only the
 * members of the groups the user is still in, blocked ones aside: the server takes an expense from
 * a current member alone, and a group the user left or was removed from takes no more of them. A
 * member who is no contact wakes the user with an expense alone, see
 * [PushSignal.Kind.GROUP_EXPENSE], which they may well have paid for the user: anything else of
 * theirs is a stranger's. The user is among them, a member of each, which changes nothing: the
 * server wakes every member but the writer.
 *
 * A request keeps its recipient on it for as long as this device holds it, which for one
 * refused is for good, its sender never being told. That lets them wake the user with one
 * signal alone, the one their acceptance sends, see [PushSignal.Kind.CONTACT_ACCEPTED]: only
 * the server sends it, as it takes the acceptance, and once per request at most, accepting
 * one consuming it and refusing one deleting it. Each request lets its recipient wake the
 * user once at most, then, and one refused not at all. The recipient goes once they are a
 * contact, at the rebuild that makes them one.
 *
 * Serialized, rebuilding and emptying alike: a rebuild that read the contacts before a removal
 * would otherwise write them back over the one that removal made.
 */
class WakeList(
    private val contactDao: ContactDao,
    private val contactRequestDao: ContactRequestDao,
    private val groupMemberDao: GroupMemberDao,
    private val store: WakeListStore?,
) : SessionLeftovers {
    private val mutex = Mutex()

    /** Replaces the list with what the database holds now. */
    suspend fun rebuild() {
        val store = store ?: return

        mutex.withLock {
            store.replace(
                contacts = contactDao.getUnblockedUserUuids().toSet(),
                requested = contactRequestDao.getUnblockedOutgoing()
                    .mapTo(mutableSetOf()) { it.recipientUserUuid },
                coMembers = groupMemberDao.getUnblockedCurrentMemberUuids().toSet(),
            )
        }
    }

    override suspend fun clear() {
        val store = store ?: return

        mutex.withLock { store.clear() }
    }
}
