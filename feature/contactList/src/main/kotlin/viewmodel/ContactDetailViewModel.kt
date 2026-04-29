package viewmodel

import ContactCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import model.User

class ContactDetailViewModel(
    userUuid: String,
    contactCallBacks: ContactCallBacks,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactDetailState())

    val state: StateFlow<ContactDetailState> = _state.onStart {
        contactCallBacks.getUser(uuid = userUuid).onEach { user ->
            _state.update {
                it.copy(user = user)
            }
        }.launchIn(viewModelScope)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = ContactDetailState(),
    )
}

data class ContactDetailState(
    val isLoading: Boolean = false,
    val user: User? = null,
)
