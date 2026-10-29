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

    /**
     * Takes [username] as typed, neither lowercased nor trimmed: the field shows it lowercased, see
     * [app.lidory.duckcount.android.ui.text.LowercaseVisualTransformation], and it is checked and
     * logged in with lowercased and without the whitespace around it, see
     * [LoginScreenState.trimmedUsername].
     */
    fun updateUsername(username: String) {
        val typed = state.copy(username = username)

        state = typed.copy(
            // Nothing typed yet isn't a mistake worth pointing out, the button
            // stays disabled anyway.
            usernameError = if (username.isBlank()) {
                null
            } else {
                authenticationCallBacks.validateUsername(username = typed.trimmedUsername)
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

    /** The username field's text, as typed. */
    val username: String = "",
    val password: String = "",
    val usernameError: UsernameInputError? = null,
    val error: AuthError? = null,
) {
    /**
     * [username] lowercased, as every username is, and without the whitespace around it, which is
     * what is checked and logged in with: one filled in with a stray space still logs in, a space
     * never being valid. The field holds it as typed: trimmed as it is typed, a space typed at the
     * end would be swallowed, and the cursor moved back over it.
     */
    val trimmedUsername: String = username.trim().lowercase()

    /**
     * Whether the form can log in: a username the rules take, and a password other than nothing
     * but whitespace, which no account holds, registration and a password change refusing it.
     */
    val canLogIn = username.isNotBlank() &&
            usernameError == null &&
            password.isNotBlank() &&
            !isLoading
}
