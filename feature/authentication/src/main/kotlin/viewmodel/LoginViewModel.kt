package viewmodel

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    val authenticationCallBacks: AuthenticationCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginScreenState())

    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    fun updateUsername(username: String) {
        _state.update {
            it.copy(username = username, error = null)
        }
    }

    fun updatePassword(password: String) {
        _state.update {
            it.copy(password = password, error = null)
        }
    }

    fun processLogin(
        username: String,
        password: String,
        navigateToExpenseListScreen: () -> Unit,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            when (val outcome = authenticationCallBacks.login(username = username, password = password)) {
                is AuthOutcome.Success -> navigateToExpenseListScreen()
                is AuthOutcome.Failure -> _state.update { it.copy(error = outcome.error) }
            }

            _state.update { it.copy(isLoading = false) }
        }
    }
}

data class LoginScreenState(
    val isLoading: Boolean = false,
    val username: String = "",
    val password: String = "",
    val error: AuthError? = null,
)
