package viewmodel

import AuthError
import AuthOutcome
import AuthenticationCallBacks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class UserInformationViewModel(
    val authenticationCallBacks: AuthenticationCallBacks,
) : ViewModel() {
    // Snapshot state rather than a flow: the field has to get its text back in the same frame as
    // the edit, which a collected flow only delivers a frame later.
    var state by mutableStateOf(UserInformationState())
        private set

    /** The longest full name the form takes, which its field stops at. */
    val maxFullNameLength = authenticationCallBacks.maxFullNameLength

    fun updateFullName(fullName: String) {
        state = state.copy(fullName = fullName, error = null)
    }

    fun insertUser(
        uuid: String,
        username: String,
        fullName: String,
        hasPasswordBeenChecked: Boolean,
    ) {
        if (state.isSaving || state.isSaved) return

        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            val outcome = authenticationCallBacks.insertUser(
                uuid = uuid,
                username = username,
                fullName = fullName,
                hasPasswordBeenChecked = hasPasswordBeenChecked,
            )

            state = when (outcome) {
                // The profile is only saved once the server holds it, so this is
                // the only outcome that finishes the registration.
                is AuthOutcome.Success -> state.copy(isSaving = false, isSaved = true)

                is AuthOutcome.Failure -> state.copy(isSaving = false, error = outcome.error)
            }
        }
    }
}

data class UserInformationState(
    val username: String = "",
    val fullName: String = "",
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: AuthError? = null,
) {
    val canSave = fullName.isNotBlank() && !isSaving
}
