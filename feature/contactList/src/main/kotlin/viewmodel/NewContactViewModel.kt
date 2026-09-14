package viewmodel

import ContactCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import model.AddContactError

class NewContactViewModel(
    private val contactCallBacks: ContactCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(NewContactState())
    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = NewContactState(),
    )

    fun updateUsername(username: String) {
        _state.update {
            it.copy(username = username, error = null, isSent = false)
        }
    }

    fun sendContactRequest() {
        val username = _state.value.username

        viewModelScope.launch {
            _state.update {
                it.copy(isSending = true, error = null, isSent = false)
            }

            val error = contactCallBacks.sendContactRequest(username = username)

            _state.update {
                it.copy(
                    // A sent request empties the field, ready for the next contact.
                    username = if (error == null) "" else it.username,
                    isSending = false,
                    error = error,
                    isSent = error == null,
                )
            }
        }
    }
}

data class NewContactState(
    val username: String = "",
    val isSending: Boolean = false,
    val error: AddContactError? = null,
    val isSent: Boolean = false,
) {
    val canSend: Boolean = username.isNotBlank() && !isSending
}
