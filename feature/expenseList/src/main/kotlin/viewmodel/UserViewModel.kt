package viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import database.user.User
import database.user.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UserViewModel(
    private val userRepository: UserRepository,
) : ViewModel() {
    private var observeUsersJob: Job? = null

    private val _state = MutableStateFlow(UsersState())
    val state = _state.onStart {
        observeUsers()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    private fun observeUsers() {
        observeUsersJob?.cancel()
        observeUsersJob = userRepository
            .getAll()
            .onEach { users ->
                _state.update {
                    it.copy(
                        users = users,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

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