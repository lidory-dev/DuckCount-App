package app.lidory.duckcount.settings.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.model.NewPasswordInputError
import app.lidory.duckcount.settings.model.PasswordChangeError
import app.lidory.duckcount.settings.model.PasswordChangeFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the password change screen. The current password typed there is what the server asks for
 * before it changes anything, see [SettingsCallBacks.changePassword].
 */
class PasswordChangeViewModel(
    private val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    // Snapshot state rather than a flow: the fields have to get their text back in the same frame
    // as the edit, which a collected flow only delivers a frame later.
    var state by mutableStateOf(
        PasswordChangeScreenState(minimumLength = settingsCallBacks.minimumPasswordStrength),
    )
        private set

    // True once the password is changed, the only thing this screen is for. A state rather than
    // an event: the change is seen through with the app in the background, where the screen
    // cannot navigate, and the screen then closes once the user is back.
    private val _isChanged = MutableStateFlow(false)
    val isChanged: StateFlow<Boolean> = _isChanged.asStateFlow()

    fun onCurrentPasswordChanged(password: String) {
        state = state.withCurrentPassword(password = password)
    }

    fun onNewPasswordChanged(password: String) {
        state = state.withNewPassword(password = password)
    }

    fun onNewPasswordConfirmationChanged(password: String) {
        state = state.withNewPasswordConfirmation(password = password)
    }

    /**
     * Forgets the leak once its screen is open, with the new password, typed twice, as registration
     * forgets its own: the user comes back to choose another one.
     */
    fun onLeakShown() {
        state = state.copy(leakAmount = null, newPassword = "", newPasswordConfirmation = "")
    }

    /** Drops [error] once the snackbar has shown it, unless something replaced it meanwhile. */
    fun onErrorShown(error: PasswordChangeError) {
        if (state.error == error) state = state.copy(error = null)
    }

    fun onConfirmClicked() {
        // Not once changed: the screen is closing, and changing again would only fail on the
        // current password, which no longer is.
        if (_isChanged.value) return

        val form = state

        // Only a change the form holds, and not while one is under way, which a second tap landing
        // before the button is disabled would start again, its handshakes replacing the first
        // one's on the server.
        if (!form.canConfirm) return

        state = form.copy(isChanging = true, error = null)

        viewModelScope.launch {
            val failure = settingsCallBacks.changePassword(
                currentPassword = form.currentPassword,
                newPassword = form.newPassword,
            )

            state = when (failure) {
                // The change is the only thing this screen is for, so a successful one hands the
                // user back to the account screen.
                null -> {
                    _isChanged.value = true
                    state.copy(isChanging = false)
                }

                is PasswordChangeFailure.Leaked -> state.copy(isChanging = false, leakAmount = failure.count)

                is PasswordChangeFailure.Form -> state.copy(isChanging = false, error = failure.error)
            }
        }
    }
}

/**
 * What the password change screen shows. Nothing typed yet in a field is no mistake worth
 * pointing out: the button stays disabled anyway.
 */
data class PasswordChangeScreenState(
    val minimumLength: Int,
    val currentPassword: String = "",
    val newPassword: String = "",
    val newPasswordConfirmation: String = "",
    val isChanging: Boolean = false,
    /** Why the last change failed, until an edit answers it or the snackbar has shown it. */
    val error: PasswordChangeError? = null,
    /**
     * How many times the new password turns up in known breaches, which a screen of its own says, as
     * at registration, until it opens, see [PasswordChangeViewModel.onLeakShown].
     */
    val leakAmount: Int? = null,
) {
    /** Why [newPassword] cannot be set, taken as typed, never trimmed, see [NewPasswordInputError]. */
    val newPasswordInputError: NewPasswordInputError? = when {
        newPassword.isEmpty() -> null
        newPassword.isBlank() -> NewPasswordInputError.BLANK
        newPassword.length < minimumLength -> NewPasswordInputError.TOO_SHORT
        newPassword == currentPassword -> NewPasswordInputError.SAME_AS_CURRENT
        else -> null
    }

    val isConfirmationMismatched: Boolean =
        newPasswordConfirmation.isNotEmpty() && newPasswordConfirmation != newPassword

    /**
     * Whether the form holds a change to send: every field filled in and none in error, a failure
     * shown on one of them included, since sending it again would fail the same way. A current
     * password of nothing but whitespace is none, as on every form: no account holds one.
     */
    val canConfirm: Boolean =
        currentPassword.isNotBlank() &&
            newPassword.isNotEmpty() &&
            newPasswordInputError == null &&
            newPasswordConfirmation == newPassword &&
            error?.isFieldError != true &&
            !isChanging

    // Each edit clears the failure it answers: the one shown on the field edited, and the one the
    // snackbar shows, which was about the form as it was. Not the one shown on another field,
    // which still holds what failed.

    fun withCurrentPassword(password: String): PasswordChangeScreenState =
        copy(currentPassword = password, error = null)

    fun withNewPassword(password: String): PasswordChangeScreenState =
        copy(newPassword = password, error = error?.takeIf { it == PasswordChangeError.WRONG_PASSWORD })

    fun withNewPasswordConfirmation(password: String): PasswordChangeScreenState =
        copy(newPasswordConfirmation = password, error = error?.takeIf { it.isFieldError })
}
