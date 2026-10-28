package app.lidory.duckcount.settings.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import app.lidory.duckcount.settings.model.AccountDeletionError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the account deletion screen. The password typed there is the whole confirmation: it is
 * what the server asks for before it deletes anything, see [SettingsCallBacks.deleteAccount].
 */
class AccountDeletionViewModel(
    private val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    // Snapshot state rather than a flow: the field has to get its text back in the same frame as
    // the edit, which a collected flow only delivers a frame later.
    var password by mutableStateOf("")
        private set

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _error = MutableStateFlow<AccountDeletionError?>(null)
    val error: StateFlow<AccountDeletionError?> = _error.asStateFlow()

    fun onPasswordChanged(password: String) {
        this.password = password
        _error.value = null
    }

    fun onConfirmClicked() {
        // Not while a deletion is under way, which a second tap landing before the button is
        // disabled would start again, its re-authentication replacing the first one's.
        if (_isDeleting.value) return

        val password = this.password

        if (password.isBlank()) return

        viewModelScope.launch {
            _isDeleting.value = true
            _error.value = null

            // Only ever back with a failure: a deletion that went through restarts the app.
            val deletionError = settingsCallBacks.deleteAccount(password = password)

            _isDeleting.value = false
            _error.value = deletionError
        }
    }
}
