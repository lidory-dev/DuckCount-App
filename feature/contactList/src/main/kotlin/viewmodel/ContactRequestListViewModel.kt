package viewmodel

import ContactCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import model.AnswerContactRequestError
import model.ContactRequest

class ContactRequestListViewModel(
    private val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    /** The answer being sent for each request, until the server has taken it. */
    private val answers = MutableStateFlow<Map<String, ContactRequestAnswer>>(emptyMap())

    private val isRefreshing = MutableStateFlow(false)

    private val _errors = Channel<AnswerContactRequestError>(Channel.BUFFERED)
    val errors: Flow<AnswerContactRequestError> = _errors.receiveAsFlow()

    val state: StateFlow<ContactRequestListState> =
        combine(
            contactCallBacks.getContactRequests(),
            answers,
            isRefreshing,
        ) { requests, answers, isRefreshing ->
            ContactRequestListState.Content(
                requests = requests,
                answers = answers,
                isRefreshing = isRefreshing,
            )
        }.onStart {
            // A request only shows once it has been opened, which the refresh does, so the
            // screen asks for one rather than relying on the contact list's.
            viewModelScope.launch {
                isRefreshing.value = true
                contactCallBacks.refreshContacts()
                isRefreshing.value = false
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ContactRequestListState.Loading,
        )

    fun accept(requestUuid: String) {
        answer(requestUuid = requestUuid, answer = ContactRequestAnswer.ACCEPT) {
            contactCallBacks.acceptContactRequest(requestUuid = requestUuid)
        }
    }

    fun refuse(requestUuid: String) {
        answer(requestUuid = requestUuid, answer = ContactRequestAnswer.REFUSE) {
            contactCallBacks.refuseContactRequest(requestUuid = requestUuid)
        }
    }

    private fun answer(
        requestUuid: String,
        answer: ContactRequestAnswer,
        send: suspend () -> AnswerContactRequestError?,
    ) {
        viewModelScope.launch {
            answers.update { it + (requestUuid to answer) }

            // A request answered successfully leaves the list on its own.
            send()?.let { _errors.send(it) }

            answers.update { it - requestUuid }
        }
    }
}

enum class ContactRequestAnswer {
    ACCEPT,
    REFUSE,
}

sealed interface ContactRequestListState {
    /** Still reading the local requests. */
    data object Loading : ContactRequestListState

    data class Content(
        val requests: List<ContactRequest>,

        /** The answer being sent for each request, keyed by the request's UUID. */
        val answers: Map<String, ContactRequestAnswer>,

        /** Whether new requests may still turn up. */
        val isRefreshing: Boolean,
    ) : ContactRequestListState
}
