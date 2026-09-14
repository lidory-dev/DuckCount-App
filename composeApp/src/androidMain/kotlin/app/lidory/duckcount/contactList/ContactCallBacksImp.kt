package app.lidory.duckcount.contactList

import ContactCallBacks
import android.util.Log
import app.lidory.duckcount.networking.NetworkConnectivity
import authenticationUtils.getDiscoveryUsernameFlow
import database.data.repository.ContactRequestRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import model.AddContactError
import model.User
import util.Result
import util.onError

private const val TAG = "ContactCallBacksImp"

@OptIn(ExperimentalCoroutinesApi::class)
class ContactCallBacksImp(
    val userRepository: UserRepository,
    private val contactRequestRepository: ContactRequestRepository,
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

    override suspend fun removeContact(userUuid: String) {
        userRepository.remove(userUuid)
    }

    override fun getReservedUsername(): Flow<String?> {
        return getDiscoveryUsernameFlow()
    }

    override fun getContactRequestCount(): Flow<Int> {
        return contactRequestRepository.getPendingCount()
    }

    override suspend fun refreshContactRequests() {
        contactRequestRepository.refresh().onError { error ->
            // Nothing to tell the user: they keep seeing the cached count, which is
            // what it is for.
            Log.w(TAG, "refreshContactRequests: could not refresh the requests: $error")
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
}
