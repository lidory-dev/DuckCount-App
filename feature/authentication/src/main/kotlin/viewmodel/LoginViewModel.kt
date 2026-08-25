package viewmodel

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import UsernameInputError
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
            it.copy(
                username = username,
                // Nothing typed yet isn't a mistake worth pointing out, the button
                // stays disabled anyway.
                usernameError = if (username.isBlank()) {
                    null
                } else {
                    authenticationCallBacks.validateUsername(username = username)
                },
                error = null,
            )
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
        // The digest the server knows the account by is derived from this username, so
        // one that does not pass the rules can never match an account.
        if (authenticationCallBacks.validateUsername(username = username) != null) return

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
    val usernameError: UsernameInputError? = null,
    val error: AuthError? = null,
) {
    val canLogIn = username.isNotBlank() &&
            usernameError == null &&
            password.isNotBlank() &&
            !isLoading
}
