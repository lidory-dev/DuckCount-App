package viewmodel

import ContactCallBacks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import model.User

@OptIn(ExperimentalCoroutinesApi::class)
class ContactListViewModel(
    val contactCallBacks: ContactCallBacks,
) : ViewModel() {
    private var _searchQuery = MutableStateFlow("")

    var searchQuery by mutableStateOf("")
        private set

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        _searchQuery.value = query
    }

    private val users: Flow<List<User>> = _searchQuery
        .flatMapLatest { query ->
            contactCallBacks.getFilteredUsers(filter = query).map {
                it.filterNot { user -> user.uuid == contactCallBacks.getAuthenticatedUserUuid() }
            }
        }

    val usersState: StateFlow<ContactListState> =
        combine(
            contactCallBacks.getReservedUsername(),
            users,
            contactCallBacks.getContactRequestCount(),
        ) { reservedUsername, users, contactRequestCount ->
            // Until a username is reserved nobody can reach this user, so the list would
            // stay empty whatever they do: ask for one instead of showing nothing.
            if (reservedUsername == null) {
                ContactListState.UsernameReservationRequired
            } else {
                ContactListState.Content(
                    users = users,
                    contactRequestCount = contactRequestCount,
                )
            }
        }.onStart {
            contactCallBacks.contactsSeen()

            // Launched rather than awaited: `onStart` runs in the collector's context, so
            // suspending here would hold the first emission back for the whole request.
            viewModelScope.launch { contactCallBacks.refreshContacts() }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ContactListState.Loading,
        )
}

sealed interface ContactListState {
    /** Still reading the local state, show neither the list nor the reservation screen. */
    data object Loading : ContactListState

    /** The user has not reserved a username yet. */
    data object UsernameReservationRequired : ContactListState

    data class Content(
        val users: List<User>,
        val contactRequestCount: Int,
    ) : ContactListState
}
