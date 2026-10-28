package viewmodel

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import UsernameInputError
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class LoginViewModel(
    val authenticationCallBacks: AuthenticationCallBacks,
) : ViewModel() {

    val maxUsernameLength = authenticationCallBacks.maxUsernameLength

    // Snapshot state rather than a flow: the fields have to get their text back in the same frame
    // as the edit, which a collected flow only delivers a frame later.
    var state by mutableStateOf(LoginScreenState())
        private set

    fun updateUsername(username: String) {
        val lowercased = username.lowercase()

        state = state.copy(
            username = lowercased,
            // Nothing typed yet isn't a mistake worth pointing out, the button
            // stays disabled anyway.
            usernameError = if (lowercased.isBlank()) {
                null
            } else {
                authenticationCallBacks.validateUsername(username = lowercased)
            },
            error = null,
        )
    }

    fun updatePassword(password: String) {
        state = state.copy(password = password, error = null)
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
            state = state.copy(isLoading = true, error = null)

            when (val outcome = authenticationCallBacks.login(username = username, password = password)) {
                is AuthOutcome.Success -> navigateToExpenseListScreen()
                is AuthOutcome.Failure -> state = state.copy(error = outcome.error)
            }

            state = state.copy(isLoading = false)
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
