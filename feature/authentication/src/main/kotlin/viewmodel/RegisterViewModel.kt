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

class RegisterViewModel(
    val authenticationCallBacks: AuthenticationCallBacks,
) : ViewModel() {

    val maxUsernameLength = authenticationCallBacks.maxUsernameLength

    val passwordStrength = authenticationCallBacks.passwordStrength

    // Snapshot state rather than a flow: the fields have to get their text back in the same frame
    // as the edit, which a collected flow only delivers a frame later.
    var state by mutableStateOf(RegisterScreenState())
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

    fun clearPasswords() {
        state = state.copy(password = "", passwordConfirmation = "", error = null)
    }

    fun updatePassword(password: String) {
        state = state.copy(password = password, error = null)
    }

    fun updatePasswordConfirmation(password: String) {
        state = state.copy(passwordConfirmation = password, error = null)
    }

    fun isPasswordConfirmationValid(): Boolean {
        return state.password == state.passwordConfirmation
    }

    fun processRegistration(
        username: String,
        password: String,
        navigateToPasswordLeakScreen: (Int) -> Unit,
        navigateToUserInformationScreen: (String, String, Boolean) -> Unit,
    ) = viewModelScope.launch {
        // The account is keyed by a digest derived from this username, so registering one
        // that does not pass the rules would mint an account nothing can log back into.
        if (authenticationCallBacks.validateUsername(username = username) != null) return@launch

        state = state.copy(isLoading = true, error = null)

        // Advisory breach check: if the password is leaked, warn and stop. If it's clean, or if
        // the check itself fails (HIBP unreachable), continue with registration (fail-open).
        val hasPasswordBeenChecked: Boolean =
            when (val leak = authenticationCallBacks.passwordLeakAmount(password)) {
                is AuthOutcome.Success -> {
                    if (leak.data > 0) {
                        navigateToPasswordLeakScreen(leak.data)
                        state = state.copy(isLoading = false)
                        return@launch
                    }

                    true
                }

                is AuthOutcome.Failure -> false
            }

        when (val result = authenticationCallBacks.registration(username = username, password = password)) {
            is AuthOutcome.Success -> {
                // Remember the in-progress registration so the "set up profile" step can be resumed if
                // the app is killed before it's completed.
                authenticationCallBacks.markPendingRegistration(result.data, username, hasPasswordBeenChecked)
                navigateToUserInformationScreen(result.data, username, hasPasswordBeenChecked)
            }

            is AuthOutcome.Failure ->
                state = state.copy(error = result.error)
        }

        state = state.copy(isLoading = false)
    }

    fun isPasswordTooShort(): Boolean {
        return state.password.length < passwordStrength
    }
}

data class RegisterScreenState(
    val isLoading: Boolean = false,
    val username: String = "",
    val password: String = "",
    val passwordConfirmation: String = "",
    val usernameError: UsernameInputError? = null,
    val error: AuthError? = null,
)
