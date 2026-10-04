package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import kotlinx.coroutines.launch

class AccountViewModel(
    private val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val username = settingsCallBacks.getUserUsername()

    /** Logs out and restarts the app, see [SettingsCallBacks.logOut]. */
    fun logOut() {
        viewModelScope.launch {
            settingsCallBacks.logOut()
        }
    }
}
