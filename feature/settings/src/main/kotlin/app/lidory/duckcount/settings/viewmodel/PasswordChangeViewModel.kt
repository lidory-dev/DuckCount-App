package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class PasswordChangeViewModel(
    val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val passwordStrength = settingsCallBacks.minimumPasswordStrength

    private val _state = MutableStateFlow(PasswordChangeScreenState())

    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    fun onCurrentPasswordChanged(password: String) {
        _state.update {
            it.copy(currentPassword = password)
        }
    }

    fun onNewPasswordChanged(password: String) {
        _state.update {
            it.copy(newPassword = password)
        }
    }

    fun onNewPasswordConfirmationChanged(password: String) {
        _state.update {
            it.copy(newPasswordConfirmation = password)
        }
    }

    fun isNewPasswordConfirmationValid(): Boolean {
        return _state.value.newPassword == _state.value.newPasswordConfirmation
    }

    fun isNewPasswordTooShort(): Boolean {
        return _state.value.newPassword.length < passwordStrength
    }

    fun onConfirmClicked() {
        // TODO: Check password against known breaches.
        TODO()
    }
}

data class PasswordChangeScreenState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val newPasswordConfirmation: String = "",
)
