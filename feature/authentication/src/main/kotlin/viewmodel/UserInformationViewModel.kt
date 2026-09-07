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

class UserInformationViewModel(
    val authenticationCallBacks: AuthenticationCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(UserInformationState())

    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    fun updateFullName(fullName: String) {
        _state.update {
            it.copy(fullName = fullName, error = null)
        }
    }

    fun insertUser(
        uuid: String,
        username: String,
        fullName: String,
        hasPasswordBeenChecked: Boolean,
    ) {
        if (_state.value.isSaving || _state.value.isSaved) return

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val outcome = authenticationCallBacks.insertUser(
                uuid = uuid,
                username = username,
                fullName = fullName,
                hasPasswordBeenChecked = hasPasswordBeenChecked,
            )

            _state.update {
                when (outcome) {
                    // The profile is only saved once the server holds it, so this is
                    // the only outcome that finishes the registration.
                    is AuthOutcome.Success -> it.copy(isSaving = false, isSaved = true)

                    is AuthOutcome.Failure -> it.copy(isSaving = false, error = outcome.error)
                }
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
