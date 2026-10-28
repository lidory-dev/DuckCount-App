package viewmodel

import ContactCallBacks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import model.AddContactError
import model.UsernameInputError

class NewContactViewModel(
    private val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    val maxUsernameLength = contactCallBacks.maxUsernameLength

    // Snapshot state rather than a flow: the username has to come back to its field in the same
    // frame as the edit. A flow collected by the screen hands it back a frame late, the field
    // meanwhile taking back the text it had, and its cursor with it.
    var state by mutableStateOf(NewContactState())
        private set

    fun updateUsername(username: String) {
        // Held as typed, neither lowercased nor trimmed: rewriting what is being typed would hand
        // the keyboard a new text at every capital, dropping the keys already on their way to it,
        // and trimming it would swallow a space typed at the end. The field shows it lowercased,
        // and what is checked and sent is lowercased and trimmed, see
        // NewContactState.trimmedUsername.
        val typed = state.copy(username = username)

        state = typed.copy(
            inputError = contactCallBacks.validateUsername(username = typed.trimmedUsername),
            error = null,
        )
    }

    fun sendContactRequest() {
        // Only what the button would send: the keyboard's send action calls this too.
        if (!state.canSend) return

        val username = state.trimmedUsername

        viewModelScope.launch {
            state = state.copy(isSending = true, error = null)

            val error = contactCallBacks.sendContactRequest(username = username)

            state = state.copy(
                // A sent request empties the field, ready for the next contact.
                username = if (error == null) "" else state.username,
                isSending = false,
                error = error,
                sentTo = if (error == null) username else state.sentTo,
            )
        }
    }

    /**
     * Called once the screen has confirmed the request to [username]. Only that one is cleared:
     * the next request can be sent before the confirmation of this one is over.
     */
    fun onSentConfirmed(username: String) {
        if (state.sentTo == username) state = state.copy(sentTo = null)
    }
}

data class NewContactState(
    /** What the user typed, as typed, emptied once a request is sent. */
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
     * [username] lowercased, as every username is, and without the whitespace around it, which is
     * what is checked and sent: one pasted with a stray space still goes through, a space never
     * being valid.
     */
    val trimmedUsername: String = username.trim().lowercase()

    val canSend: Boolean = username.isNotBlank() && inputError == null && !isSending
}
