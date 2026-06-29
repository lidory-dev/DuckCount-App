package viewmodel

import ContactCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import model.User

class ContactDetailViewModel(
    userUuid: String,
    val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    private val _navigateBack = Channel<Unit>(Channel.BUFFERED)
    val navigateBack: Flow<Unit> = _navigateBack.receiveAsFlow()

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

    fun removeContact() {
        viewModelScope.launch {
            _state.value.user?.let { user ->
                contactCallBacks.removeContact(user.uuid)
                _navigateBack.send(Unit)
            }
        }
    }
}

data class ContactDetailState(
    val isLoading: Boolean = false,
    val user: User? = null,
)
