package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AccountViewModel(
    private val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {

    val username = settingsCallBacks.getUserUsername()

    private val _navigateBack = Channel<Unit>(Channel.BUFFERED)
    val navigateBack: Flow<Unit> = _navigateBack.receiveAsFlow()

    fun logOut() {
        viewModelScope.launch {
            settingsCallBacks.logOut()

            _navigateBack.send(Unit)
        }
    }
}
