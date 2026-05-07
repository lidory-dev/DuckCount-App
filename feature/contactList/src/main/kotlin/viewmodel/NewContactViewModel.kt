package viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class NewContactViewModel() : ViewModel() {
    private val _state = MutableStateFlow(NewContactState())
    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = NewContactState(),
    )

    fun updateUsername(username: String) {
        _state.update {
            it.copy(username = username)
        }
    }
}

data class NewContactState(
    val username: String = "",
)
