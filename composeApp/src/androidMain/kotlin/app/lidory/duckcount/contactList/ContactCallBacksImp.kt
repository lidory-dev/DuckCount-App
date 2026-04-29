package app.lidory.duckcount.contactList

import ContactCallBacks
import database.user.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import model.User
import viewmodel.ContactListState

@OptIn(ExperimentalCoroutinesApi::class)
class ContactCallBacksImp(
    val userRepository: UserRepository,
) : ContactCallBacks {
    override suspend fun getFilteredUsers(filter: String): Flow<ContactListState> {
        return userRepository.getAllFiltered(filter)
            .map { users ->
                ContactListState(
                    users = users.map {
                        User(
                            uuid = it.uuid,
                            username = it.username,
                            fullName = it.fullName,
                        )
                    },
                )
            }
    }
}
