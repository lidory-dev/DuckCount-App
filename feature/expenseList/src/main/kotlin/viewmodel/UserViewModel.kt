package viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import database.user.User
import database.user.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UserViewModel(
    private val userRepository: UserRepository,
) : ViewModel() {

    private var _searchQuery = MutableStateFlow("")
    var searchQuery by mutableStateOf("")
        private set

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        _searchQuery.value = query
    }

    fun clearSearchQuery() {
        searchQuery = ""
        _searchQuery.value = ""
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UsersState> = _searchQuery
        .flatMapLatest { query ->
            userRepository.getAllFiltered(filter = query)
        }
        .map { users ->
            UsersState(users = users)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UsersState(),
        )

    fun createUser(
        user: User,
    ) = viewModelScope.launch {
        userRepository.insert(user = user)
    }
}

data class UsersState(
    val isLoading: Boolean = false,
    val users: List<User> = emptyList(),
)