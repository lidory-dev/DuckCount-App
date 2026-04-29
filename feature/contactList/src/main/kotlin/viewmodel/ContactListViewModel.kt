package viewmodel

import ContactCallBacks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
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

    val usersState: StateFlow<ContactListState> = _searchQuery
        .flatMapLatest { query ->
            contactCallBacks.getFilteredUsers(filter = query)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ContactListState(),
        )

}

data class ContactListState(
    val isLoading: Boolean = false,
    val users: List<User> = emptyList(),
)
