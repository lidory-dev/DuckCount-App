package contact

import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import message.DirectMessageSync
import message.DirectMessageSyncError
import push.WakeList
import util.EmptyResult
import util.Result
import util.onError

/** What one [ContactSync.sync] came to. */
class ContactSyncPass(
    /**
     * Whether everything the server held for the user was taken in, whatever came of the backup
     * after it: whoever became a contact of the user by then is one on this device too, see
     * [group.GroupSync.sync].
     */
    val isTakenIn: Boolean,

    /** The first thing that kept the sync from doing all it had to, or null when nothing did. */
    val failure: ContactSyncError?,
)

/**
 * Brings this device's contacts and contact requests in line with everything the
 * server is holding for the user, and carries what changed back to the vault.
 *
 * One sequence, in one place, because two callers need exactly the same one: the
 * screens that refresh when they open, and the worker a push signal starts. What the
 * server holds is drained and acknowledged as it goes, so a sync that stops halfway
 * leaves the rest for the next one rather than losing it.
 */
class ContactSync(
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val directMessageSync: DirectMessageSync,
    private val profileKeyDelivery: ProfileKeyDelivery,
    private val profileKeyPull: ProfileKeyPull,
    private val wakeList: WakeList,
) {
    /**
     * Takes in what the server holds for the user, then [backUp]s what came of it, and says
     * whether all of it was taken in, see [ContactSyncPass].
     *
     * Every step is attempted even when one fails, and the first failure is
     * reported: they are independent of each other, and whatever did not get done is
     * picked up by the next sync.
     */
    suspend fun sync(): ContactSyncPass {
        var failure: ContactSyncError? = null

        contactRequestRepository.refresh().onError { failure = failure ?: it }

        directMessageSync.sync().onError { failure = failure ?: it.toContactSyncError() }

        // The only steps that bring anybody in: the backup failing says nothing of who is a contact.
        val isTakenIn = failure == null

        backUp().onError { failure = failure ?: it }

        return ContactSyncPass(isTakenIn = isTakenIn, failure = failure)
    }

    /**
     * Carries the contact changes this device made to the vault, hands the key to the user's
     * profile to the contacts who do not hold it yet, reads the profiles of the contacts it has
     * no name for, or was missing the current key of and has a new one from, see
     * [ContactRepository.resolveNames], asks those whose key it is missing for it, and rebuilds
     * who may wake the user, see [WakeList].
     *
     * What every operation that changes a contact ends with: the change itself
     * already landed on this device, and this is what makes it outlive the device.
     * The list is rebuilt whatever came of the rest: the changes that matter rebuilt it as
     * they landed, and this catches whatever was left behind, by a process killed in
     * between for instance.
     *
     * The key is handed out right after the backup, which is where it rotates when the user
     * removed a contact, so the contacts left get the new one at once, see
     * [ProfileKeyDelivery]. An edit of the profile made before then rotates it instead, see
     * `ProfileRepository.uploadProfile`, and the next backup hands that one out. Who was handed
     * it is backed up in turn, which writes nothing when nobody was.
     *
     * The contacts' keys are asked for once the names are read, which is what tells which keys
     * no longer open their owner's latest profile, see [ProfileKeyPull].
     */
    suspend fun backUp(): EmptyResult<ContactSyncError> {
        var failure: ContactSyncError? = null

        val backUp = contactRepository.backUp().onError { failure = failure ?: it }

        profileKeyDelivery.handOut().onError { failure = failure ?: it }

        // Not after a backup that failed: it would only try the same write again at once, a
        // rotation of the key included, and the next sync carries who was handed it.
        if (backUp is Result.Success) contactRepository.backUp().onError { failure = failure ?: it }

        contactRepository.resolveNames().onError { failure = failure ?: it }

        profileKeyPull.requestMissingKeys().onError { failure = failure ?: it }

        wakeList.rebuild()

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }
}

/**
 * The same failure, told the way a contact sync tells it.
 *
 * The messages waiting for a user are no longer only about contacts, so draining them is
 * its own thing; what it can fail with is still a subset of what this reports.
 */
private fun DirectMessageSyncError.toContactSyncError(): ContactSyncError =
    when (this) {
        is DirectMessageSyncError.Identity -> ContactSyncError.Identity(error = error)
        DirectMessageSyncError.Storage -> ContactSyncError.Storage
        is DirectMessageSyncError.Contacts -> error
        is DirectMessageSyncError.Network -> ContactSyncError.Network(error = error)
    }
