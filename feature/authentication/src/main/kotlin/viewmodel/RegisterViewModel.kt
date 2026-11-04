package viewmodel

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import PasswordInputError
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

    /**
     * Takes [username] as typed, neither lowercased nor trimmed: the field shows it lowercased, see
     * [app.lidory.duckcount.android.ui.text.LowercaseVisualTransformation], and it is checked and
     * registered lowercased and without the whitespace around it, see
     * [RegisterScreenState.trimmedUsername].
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

    /**
     * Whether the confirmation, once anything is typed in it, whitespace included, differs from
     * the password, which the form points out, as the password change form does.
     */
    fun isPasswordConfirmationMismatched(): Boolean =
        state.passwordConfirmation.isNotEmpty() && !isPasswordConfirmationValid()

    fun processRegistration(
        username: String,
        password: String,
        navigateToPasswordLeakScreen: (Int) -> Unit,
        navigateToUserInformationScreen: (String, String, Boolean) -> Unit,
        navigateToDeviceNotAllowedScreen: () -> Unit,
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
                // Nothing on the form would change this answer, so it is no error of the form's.
                if (result.error == AuthError.DEVICE_NOT_ALLOWED) {
                    navigateToDeviceNotAllowedScreen()
                } else {
                    state = state.copy(error = result.error)
                }
        }

        state = state.copy(isLoading = false)
    }

    /**
     * Why [RegisterScreenState.password] cannot be registered, see [PasswordInputError], or null
     * while nothing is typed yet, which is no mistake worth pointing out: the button stays disabled
     * anyway, see [canRegister]. The password is taken as typed, never trimmed.
     */
    fun passwordError(): PasswordInputError? = when {
        state.password.isEmpty() -> null
        state.password.isBlank() -> PasswordInputError.BLANK
        state.password.length < passwordStrength -> PasswordInputError.TOO_SHORT
        else -> null
    }

    /**
     * Whether the form holds a registration to send, from its button or the keyboard's Done
     * alike: a username the rules take, a password with nothing wrong with it, typed again the
     * same, and no registration under way.
     */
    fun canRegister(): Boolean =
        state.username.isNotBlank() &&
            state.usernameError == null &&
            state.password.isNotEmpty() &&
            passwordError() == null &&
            isPasswordConfirmationValid() &&
            !state.isLoading
}

data class RegisterScreenState(
    val isLoading: Boolean = false,

    /** The username field's text, as typed. */
    val username: String = "",
    val password: String = "",
    val passwordConfirmation: String = "",
    val usernameError: UsernameInputError? = null,
    val error: AuthError? = null,
) {
    /**
     * [username] lowercased, as every username is, and without the whitespace around it, which is
     * what is checked and registered: one filled in with a stray space still goes through, a space
     * never being valid. The field holds it as typed: trimmed as it is typed, a space typed at the
     * end would be swallowed, and the cursor moved back over it.
     */
    val trimmedUsername: String = username.trim().lowercase()
}
