package app.lidory.duckcount.contactList

import ContactCallBacks
import android.util.Log
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.push.ContactNotifier
import authenticationUtils.getDiscoveryUsernameFlow
import contact.AnswerContactRequestError as SharedAnswerContactRequestError
import contact.ContactArrivals
import contact.ContactSync
import database.data.repository.ContactRepository
import database.data.repository.ContactRequestRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import model.AddContactError
import model.AnswerContactRequestError
import model.ContactRequest
import model.RemoveContactError
import model.User
import util.Result
import util.onError

private const val TAG = "ContactCallBacksImp"

@OptIn(ExperimentalCoroutinesApi::class)
class ContactCallBacksImp(
    val userRepository: UserRepository,
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val contactSync: ContactSync,
    private val contactArrivals: ContactArrivals,
    private val contactNotifier: ContactNotifier,
    private val networkConnectivity: NetworkConnectivity,
) : ContactCallBacks {
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
        contactSync.sync().onError { error ->
            Log.w(TAG, "refreshContacts: could not sync the contacts: $error")
        }

        // Nothing is announced from here, the user is looking at the screen. What this
        // does have to do is take down what a push announced and this sync just undid:
        // a contact who removed the user while their notification was still up.
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
