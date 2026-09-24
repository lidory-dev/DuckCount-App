package contact

import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import message.DirectMessageSync
import message.DirectMessageSyncError
import util.EmptyResult
import util.Result
import util.onError

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
) {
    /**
     * Takes in what the server holds for the user, then [backUp]s what came of it.
     *
     * Every step is attempted even when one fails, and the first failure is
     * reported: they are independent of each other, and whatever did not get done is
     * picked up by the next sync.
     */
    suspend fun sync(): EmptyResult<ContactSyncError> {
        var failure: ContactSyncError? = null

        contactRequestRepository.refresh().onError { failure = failure ?: it }

        directMessageSync.sync().onError { failure = failure ?: it.toContactSyncError() }

        backUp().onError { failure = failure ?: it }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Carries the contact changes this device made to the vault, and reads the names
     * of the contacts it has none for.
     *
     * What every operation that changes a contact ends with: the change itself
     * already landed on this device, and this is what makes it outlive the device.
     */
    suspend fun backUp(): EmptyResult<ContactSyncError> {
        var failure: ContactSyncError? = null

        contactRepository.backUp().onError { failure = failure ?: it }

        contactRepository.resolveNames().onError { failure = failure ?: it }

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
        is DirectMessageSyncError.Vault -> ContactSyncError.Vault(error = error)
        is DirectMessageSyncError.Network -> ContactSyncError.Network(error = error)
    }
