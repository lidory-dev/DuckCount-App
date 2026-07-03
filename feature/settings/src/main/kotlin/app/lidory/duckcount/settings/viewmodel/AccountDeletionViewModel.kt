package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import app.lidory.duckcount.settings.SettingsCallBacks
import kotlinx.coroutines.flow.MutableStateFlow

class AccountDeletionViewModel(
    val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {
    var password = MutableStateFlow("")

    fun onPasswordChanged(password: String) {
        this.password.value = password
    }

    fun onConfirmClicked() {
        TODO()
    }
}
