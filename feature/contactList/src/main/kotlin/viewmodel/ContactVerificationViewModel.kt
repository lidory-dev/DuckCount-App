package viewmodel

import ContactCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import model.ContactVerification
import model.User

class ContactVerificationViewModel(
    private val userUuid: String,
    private val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    /** Whether a mark is being written or cleared, which the user cannot start twice. */
    private val isUpdating = MutableStateFlow(false)

    /**
     * The name on the contact's profile, null until read: the safety number does not wait for it.
     */
    private val fullName = flow { emitAll(contactCallBacks.getUser(uuid = userUuid)) }
        .map<User, String?> { user -> user.fullName }
        .onStart { emit(null) }

    val state: StateFlow<ContactVerificationState> =
        combine(
            fullName,
            contactCallBacks.getVerification(userUuid = userUuid),
            isUpdating,
        ) { fullName, verification, isUpdating ->
            ContactVerificationState(
                fullName = fullName,
                verification = verification,
                isUpdating = isUpdating,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ContactVerificationState(),
        )

    /** Marks the safety number shown verified, or clears the mark, as it is shown now. */
    fun toggleVerified() {
        val shown = state.value.verification as? ContactVerification.Available ?: return

        viewModelScope.launch {
            if (isUpdating.value) return@launch

            isUpdating.value = true

            try {
                if (shown.isVerified) {
                    contactCallBacks.clearVerification(userUuid = userUuid)
                } else {
                    // The number the user read, which is the one they vouch for.
                    contactCallBacks.markVerified(userUuid = userUuid, safetyNumber = shown.safetyNumber)
                }
            } finally {
                isUpdating.value = false
            }
        }
    }
}

data class ContactVerificationState(
    /** The name on the contact's profile, null until read. */
    val fullName: String? = null,

    /** Where the user stands with the contact, null until known. */
    val verification: ContactVerification? = null,

    /** Whether a mark is being written or cleared, which the user cannot start twice. */
    val isUpdating: Boolean = false,
)
