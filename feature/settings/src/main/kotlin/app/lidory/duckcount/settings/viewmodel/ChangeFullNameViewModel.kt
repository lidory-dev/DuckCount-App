package app.lidory.duckcount.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.settings.SettingsCallBacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChangeFullNameViewModel(
    private val settingsCallBacks: SettingsCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(ChangeFullNameState())
    val state = _state

    init {
        loadAuthenticatedMember()
    }

    private fun loadAuthenticatedMember() {
        val authenticatedUserUuid = settingsCallBacks.getAuthenticatedUserUuid() ?: return

        viewModelScope.launch {
            val fullName = settingsCallBacks.getUserFullName(authenticatedUserUuid).first()
                ?: authenticatedUserUuid

            _state.update {
                it.copy(
                    fullName = fullName,
                )
            }
        }
    }

    fun updateFullName(fullName: String) {
        _state.update {
            it.copy(fullName = fullName)
        }
    }

    fun confirmChangeFullName() {
        val authenticatedUserUuid = settingsCallBacks.getAuthenticatedUserUuid() ?: return
        
        viewModelScope.launch {
            settingsCallBacks.updateFullName(authenticatedUserUuid, _state.value.fullName)
        }
    }
}

data class ChangeFullNameState(
    val fullName: String = "",
)
