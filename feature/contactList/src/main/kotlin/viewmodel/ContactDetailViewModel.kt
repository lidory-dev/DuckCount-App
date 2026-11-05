package viewmodel

import ContactCallBacks
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import model.blockMessageRes
import model.messageRes

class ContactDetailViewModel(
    private val userUuid: String,
    val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    // True once the contact is removed, or blocked, which removes them, and leaves the screen
    // nothing to show. A state rather than an event: the removal can complete while the app is in
    // the background, where the screen cannot navigate, and the screen then closes once the user
    // is back.
    private val _isRemoved = MutableStateFlow(false)
    val isRemoved: StateFlow<Boolean> = _isRemoved.asStateFlow()

    private val _errors = Channel<ContactDetailError>(Channel.BUFFERED)
    val errors: Flow<ContactDetailError> = _errors.receiveAsFlow()

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
            // Not once removed: they are no longer a contact, so a second removal could only fail.
            if (_isRemoved.value || _state.value.isBlocking) return@launch

            _state.update { it.copy(isRemoving = true) }

            // By their UUID rather than [ContactDetailState.user]: a contact whose name is not
            // read yet has none, opened from a group member's sheet say, and is removed as any
            // other.
            val error = contactCallBacks.removeContact(userUuid)

            // There is no detail left to show.
            if (error == null) _isRemoved.value = true

            _state.update { it.copy(isRemoving = false) }
            error?.let { _errors.send(ContactDetailError.Removal(error = it)) }
        }
    }

    /** Blocks the contact, which removes them first, see [ContactCallBacks.blockContact]. */
    fun blockContact() {
        viewModelScope.launch {
            // Not once removed, nor while a removal is under way, which blocking would repeat.
            if (_isRemoved.value || _state.value.isRemoving) return@launch

            _state.update { it.copy(isBlocking = true) }

            // By their UUID, as for a removal.
            val error = contactCallBacks.blockContact(userUuid)

            // Removed as they were blocked: there is no detail left to show.
            if (error == null) _isRemoved.value = true

            _state.update { it.copy(isBlocking = false) }
            error?.let { _errors.send(ContactDetailError.Blocking(error = it)) }
        }
    }
}

data class ContactDetailState(
    val isLoading: Boolean = false,
    val user: User? = null,

    /** Whether the contact is being removed, which the user cannot start twice, nor block them meanwhile. */
    val isRemoving: Boolean = false,

    /** Whether the contact is being blocked, the same way. */
    val isBlocking: Boolean = false,
)

/** What the user could not do from the detail screen, and why. */
sealed interface ContactDetailError {

    /** Removing the contact failed. */
    data class Removal(val error: RemoveContactError) : ContactDetailError

    /** Blocking the contact failed, while removing them, which comes first. */
    data class Blocking(val error: RemoveContactError) : ContactDetailError
}

@StringRes
fun ContactDetailError.messageRes(): Int = when (this) {
    is ContactDetailError.Removal -> error.messageRes()
    is ContactDetailError.Blocking -> error.blockMessageRes()
}
