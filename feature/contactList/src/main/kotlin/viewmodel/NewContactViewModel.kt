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
import model.UsernameInputError

class NewContactViewModel(
    private val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    val maxUsernameLength = contactCallBacks.maxUsernameLength

    private val _state = MutableStateFlow(NewContactState())
    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = NewContactState(),
    )

    fun updateUsername(username: String) {
        // Lowercased like the usernames are reserved, but not trimmed: trimming what is being
        // typed would swallow a space typed at the end and move the cursor past one typed at the
        // start. What is checked and sent is trimmed instead (see NewContactState.trimmedUsername).
        val typed = username.lowercase()

        _state.update {
            it.copy(
                username = typed,
                inputError = contactCallBacks.validateUsername(username = typed.trim()),
                error = null,
            )
        }
    }

    fun sendContactRequest() {
        // Only what the button would send: the keyboard's send action calls this too.
        if (!_state.value.canSend) return

        val username = _state.value.trimmedUsername

        viewModelScope.launch {
            _state.update {
                it.copy(isSending = true, error = null)
            }

            val error = contactCallBacks.sendContactRequest(username = username)

            _state.update {
                it.copy(
                    // A sent request empties the field, ready for the next contact.
                    username = if (error == null) "" else it.username,
                    isSending = false,
                    error = error,
                    sentTo = if (error == null) username else it.sentTo,
                )
            }
        }
    }

    /**
     * Called once the screen has confirmed the request to [username]. Only that one is cleared:
     * the next request can be sent before the confirmation of this one is over.
     */
    fun onSentConfirmed(username: String) {
        _state.update {
            if (it.sentTo == username) it.copy(sentTo = null) else it
        }
    }
}

data class NewContactState(
    /** What the user typed, lowercased, emptied once a request is sent. */
    val username: String = "",

    /** Why [trimmedUsername] cannot be anybody's, null while nothing is typed. */
    val inputError: UsernameInputError? = null,

    /** Whether a request is being sent, which the user can neither repeat nor leave. */
    val isSending: Boolean = false,

    /** Why the last request could not be sent, cleared as soon as the username changes. */
    val error: AddContactError? = null,

    /**
     * The username the last request went to, until the screen has confirmed it to the user. A
     * state rather than an event: the request can complete while the app is in the background,
     * where nothing can be shown, and it is confirmed once the user is back.
     */
    val sentTo: String? = null,
) {
    /**
     * [username] without the whitespace around it, which is what is checked and sent: one pasted
     * with a stray space still goes through, a space never being valid.
     */
    val trimmedUsername: String = username.trim()

    val canSend: Boolean = username.isNotBlank() && inputError == null && !isSending
}
