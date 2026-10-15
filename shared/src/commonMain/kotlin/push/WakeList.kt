package push

import authenticationUtils.SessionLeftovers
import database.data.local.dao.ContactDao
import database.data.local.dao.ContactRequestDao
import database.data.local.entity.OutgoingContactRequestEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.nowEpochMilliseconds

/**
 * Who may wake the user: their contacts, blocked ones aside, and whoever they sent a contact
 * request in the last week still waiting for its answer, see [PushSignal.mayWake].
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
 * A request keeps its recipient on it for a week. One refused is never answered, as far as
 * this device knows, its sender not being told, so none is kept on longer: whoever refused it
 * could otherwise wake the phone with a message for good, for a sync that finds nothing to
 * show. An acceptance after the week still arrives, with the next sync rather than its own
 * push. The recipient goes at the first rebuild after it: the sync a message of theirs starts
 * ends with one.
 *
 * Serialized, rebuilding and emptying alike: a rebuild that read the contacts before a removal
 * would otherwise write them back over the one that removal made.
 */
class WakeList(
    private val contactDao: ContactDao,
    private val contactRequestDao: ContactRequestDao,
    private val store: WakeListStore?,
) : SessionLeftovers {
    private val mutex = Mutex()

    /** Replaces the list with what the database holds now. */
    suspend fun rebuild() {
        val store = store ?: return

        mutex.withLock {
            store.replace(
                contacts = contactDao.getUnblockedUserUuids().toSet(),
                requested = wakingRecipients(
                    requests = contactRequestDao.getUnblockedOutgoing(),
                    now = nowEpochMilliseconds(),
                ),
            )
        }
    }

    override suspend fun clear() {
        val store = store ?: return

        mutex.withLock { store.clear() }
    }
}

/**
 * The recipients of the [requests] the user sent who may still wake them at [now], see
 * [WakeList].
 */
internal fun wakingRecipients(requests: List<OutgoingContactRequestEntity>, now: Long): Set<String> =
    requests
        .filter { request ->
            // Negative when the clock was set back since, which leaves no telling how old the
            // request is, so it is let go rather than kept for however long the clock takes
            // to catch up.
            val age = now - request.sentAt

            age in 0..<REQUEST_WAKE_WINDOW_MILLISECONDS
        }
        .mapTo(mutableSetOf()) { it.recipientUserUuid }

/**
 * How long a contact request the user sent lets its recipient wake them: long enough for most
 * answers, short enough that one refused, which this device never hears of, stops waking it.
 */
private const val REQUEST_WAKE_WINDOW_MILLISECONDS = 7L * 24 * 60 * 60 * 1000
