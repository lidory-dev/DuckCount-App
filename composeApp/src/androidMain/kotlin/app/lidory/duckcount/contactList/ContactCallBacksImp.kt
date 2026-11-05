package app.lidory.duckcount.contactList

import ContactCallBacks
import android.util.Log
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.push.ContactNotifier
import app.lidory.duckcount.usernameMaximumLength
import app.lidory.duckcount.validateUsername as validateUsernameShared
import authenticationUtils.getDiscoveryUsernameFlow
import contact.AnswerContactRequestError as SharedAnswerContactRequestError
import contact.ContactArrivals
import contact.ContactSync
import contact.SafetyNumbers
import database.data.repository.BlockedUserRepository
import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import model.AddContactError
import model.AnswerContactRequestError
import model.BlockedUser
import model.ContactRequest
import model.ContactVerification
import model.RemoveContactError
import model.User
import model.UsernameInputError
import util.Result
import util.onError

private const val TAG = "ContactCallBacksImp"

@OptIn(ExperimentalCoroutinesApi::class)
class ContactCallBacksImp(
    val userRepository: UserRepository,
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val contactSync: ContactSync,
    private val contactArrivals: ContactArrivals,
    private val contactNotifier: ContactNotifier,
    private val safetyNumbers: SafetyNumbers,
    private val networkConnectivity: NetworkConnectivity,
) : ContactCallBacks {

    override val maxUsernameLength: Int = usernameMaximumLength()

    override suspend fun getFilteredUsers(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter)
            .map { users ->
                users.map {
                    User(
                        uuid = it.uuid,
                        username = it.username,
                        fullName = it.fullName,
                        profilePicture = null, // TODO:
                    )
                }
            }
    }

    override suspend fun getUser(uuid: String): Flow<User> {
        return userRepository.get(uuid = uuid)
            .map { user ->
                User(
                    uuid = user.uuid,
                    username = user.username,
                    fullName = user.fullName,
                )
            }
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override suspend fun removeContact(userUuid: String): RemoveContactError? =
        when (val removal = contactRepository.remove(userUuid = userUuid)) {
            is Result.Success -> {
                // The contact is told and gone from this device, so a vault write failing
                // from here on is retried by the next refresh rather than reported.
                syncContacts(caller = "removeContact")

                // The same rule as for a contact who removed the user: what a notification
                // announced about them is no longer true, whichever side ended it.
                contactNotifier.forget(userUuids = listOf(userUuid))

                null
            }

            is Result.Error -> {
                Log.w(TAG, "removeContact: could not remove the contact: ${removal.error}")

                removal.error.toRemoveContactError(isOnline = networkConnectivity.isOnline())
            }
        }

    override suspend fun blockContact(userUuid: String): RemoveContactError? =
        when (val blocking = contactRepository.block(userUuid = userUuid)) {
            is Result.Success -> {
                // Removed and blocked on this device, so a vault write failing from here on is
                // retried by the next refresh rather than reported, as for a removal.
                syncContacts(caller = "blockContact")

                // As for a removal: what a notification announced about them is no longer true.
                contactNotifier.forget(userUuids = listOf(userUuid))

                null
            }

            is Result.Error -> {
                Log.w(TAG, "blockContact: could not block the contact: ${blocking.error}")

                blocking.error.toRemoveContactError(isOnline = networkConnectivity.isOnline())
            }
        }

    override fun getBlockedUsers(): Flow<List<BlockedUser>> =
        blockedUserRepository.getBlocked().map { blocked ->
            blocked.map { BlockedUser(userUuid = it.userUuid, fullName = it.fullName) }
        }

    override suspend fun unblock(userUuid: String) {
        blockedUserRepository.unblock(userUuid = userUuid)

        // Lifted on this device, which is what the user sees, so a vault write failing from here
        // on is retried by the next refresh rather than reported.
        syncContacts(caller = "unblock")
    }

    override fun getVerification(userUuid: String): Flow<ContactVerification> {
        return safetyNumbers.observe(userUuid = userUuid).map { verification ->
            verification.toContactVerification(isOnline = networkConnectivity.isOnline())
        }
    }

    override suspend fun markVerified(userUuid: String, safetyNumber: String) {
        safetyNumbers.markVerified(userUuid = userUuid, safetyNumber = safetyNumber)

        // Marked on this device, which is what the user sees, so a vault write failing from here
        // on is retried by the next refresh rather than reported.
        syncContacts(caller = "markVerified")
    }

    override suspend fun clearVerification(userUuid: String) {
        safetyNumbers.clearMark(userUuid = userUuid)

        // Same as for marking it.
        syncContacts(caller = "clearVerification")
    }

    override fun getReservedUsername(): Flow<String?> {
        return getDiscoveryUsernameFlow()
    }

    override fun getContactRequestCount(): Flow<Int> {
        return contactRequestRepository.getPendingCount()
    }

    override fun getContactRequests(): Flow<List<ContactRequest>> {
        return contactRequestRepository.getPending().map { requests ->
            requests.map {
                ContactRequest(
                    uuid = it.uuid,
                    senderUserUuid = it.senderUserUuid,
                    senderFullName = it.senderFullName,
                )
            }
        }
    }

    override suspend fun refreshContacts() {
        val before = contactArrivals.snapshot()

        // Nothing to tell the user about a failure: they keep seeing what they already
        // had, which is what caching it is for, and the next refresh tries again.
        contactSync.sync().failure?.let { error ->
            Log.w(TAG, "refreshContacts: could not sync the contacts: $error")
        }

        // Nothing is announced from here, the user is looking at the screen. What this
        // does have to do is take down what a push announced and this sync just undid:
        // a contact who removed the user, or deleted their account, while their notification
        // was still up.
        contactNotifier.forget(userUuids = contactArrivals.departures(snapshot = before))
    }

    override fun contactsSeen() = contactNotifier.clearAcceptedContacts()

    override fun contactRequestsSeen() = contactNotifier.clearContactRequests()

    override suspend fun acceptContactRequest(requestUuid: String): AnswerContactRequestError? =
        when (val acceptance = contactRequestRepository.accept(requestUuid = requestUuid)) {
            is Result.Success -> {
                // The request is answered and its sender stored on this device, so whatever
                // fails from here on is retried by the next refresh rather than reported.
                syncContacts(caller = "acceptContactRequest")

                null
            }

            is Result.Error -> {
                Log.w(TAG, "acceptContactRequest: could not accept the request: ${acceptance.error}")

                acceptance.error.toAnswerContactRequestError(isOnline = networkConnectivity.isOnline())
            }
        }

    override suspend fun refuseContactRequest(requestUuid: String): AnswerContactRequestError? =
        when (val refusal = contactRequestRepository.refuse(requestUuid = requestUuid)) {
            is Result.Success -> null

            is Result.Error -> {
                Log.w(TAG, "refuseContactRequest: could not refuse the request: ${refusal.error}")

                // Not refused, accepted: its sender is a contact now, stored like after an
                // acceptance, and backed up the same way.
                if (refusal.error == SharedAnswerContactRequestError.AlreadyAccepted) {
                    syncContacts(caller = "refuseContactRequest")
                }

                refusal.error.toAnswerContactRequestError(isOnline = networkConnectivity.isOnline())
            }
        }

    override suspend fun blockContactRequestSender(requestUuid: String): AnswerContactRequestError? =
        when (val blocking = contactRequestRepository.blockSender(requestUuid = requestUuid)) {
            is Result.Success -> {
                // Blocked on this device, so a vault write failing from here on is retried by the
                // next refresh rather than reported.
                syncContacts(caller = "blockContactRequestSender")

                null
            }

            is Result.Error -> {
                Log.w(TAG, "blockContactRequestSender: could not block the sender: ${blocking.error}")

                // Not refused, accepted, as for a refusal: its sender is a contact now.
                if (blocking.error == SharedAnswerContactRequestError.AlreadyAccepted) {
                    syncContacts(caller = "blockContactRequestSender")
                }

                blocking.error.toAnswerContactRequestError(isOnline = networkConnectivity.isOnline())
            }
        }

    override fun validateUsername(username: String): UsernameInputError? {
        return validateUsernameShared(username = username)?.toUsernameInputError()
    }

    override suspend fun sendContactRequest(username: String): AddContactError? =
        when (val request = contactRequestRepository.sendContactRequest(username = username)) {
            is Result.Success -> null

            is Result.Error -> {
                Log.w(TAG, "sendContactRequest: could not send the request: ${request.error}")

                request.error.toAddContactError(isOnline = networkConnectivity.isOnline())
            }
        }

    /** Backs up the contacts the vault does not hold yet, and reads the missing names. */
    private suspend fun syncContacts(caller: String) {
        contactSync.backUp().onError { error ->
            Log.w(TAG, "$caller: could not back the contacts up: $error")
        }
    }
}
