package app.lidory.duckcount.contactList

import ContactCallBacks
import database.domain.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import model.User

@OptIn(ExperimentalCoroutinesApi::class)
class ContactCallBacksImp(
    val userRepository: UserRepository,
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
}
