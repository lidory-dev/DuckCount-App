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
import model.ContactVerification
import model.RemoveContactError
import model.User

class ContactDetailViewModel(
    userUuid: String,
    val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    private val _navigateBack = Channel<Unit>(Channel.BUFFERED)
    val navigateBack: Flow<Unit> = _navigateBack.receiveAsFlow()

    private val _errors = Channel<RemoveContactError>(Channel.BUFFERED)
    val errors: Flow<RemoveContactError> = _errors.receiveAsFlow()

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

    /** Where the user stands with checking the contact's identity, null until known. */
    val verification: StateFlow<ContactVerification?> =
        contactCallBacks.getVerification(userUuid = userUuid)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null,
            )

    fun removeContact() {
        viewModelScope.launch {
            _state.value.user?.let { user ->
                _state.update { it.copy(isRemoving = true) }

                when (val error = contactCallBacks.removeContact(user.uuid)) {
                    // There is no detail left to show.
                    null -> _navigateBack.send(Unit)

                    else -> {
                        _state.update { it.copy(isRemoving = false) }
                        _errors.send(error)
                    }
                }
            }
        }
    }
}

data class ContactDetailState(
    val isLoading: Boolean = false,
    val user: User? = null,

    /** Whether the contact is being removed, which the user cannot start twice. */
    val isRemoving: Boolean = false,
)
