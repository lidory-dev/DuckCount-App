package viewmodel

import ContactCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import model.BlockedUser

class BlockedUserListViewModel(
    private val contactCallBacks: ContactCallBacks,
) : ViewModel() {

    val state: StateFlow<BlockedUserListState> =
        contactCallBacks.getBlockedUsers()
            .map { BlockedUserListState.Content(users = it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = BlockedUserListState.Loading,
            )

    /** Lifts the block on [userUuid], who leaves the list as it lands, see [ContactCallBacks.unblock]. */
    fun unblock(userUuid: String) {
        viewModelScope.launch { contactCallBacks.unblock(userUuid = userUuid) }
    }
}

sealed interface BlockedUserListState {
    /** Still reading who is blocked. */
    data object Loading : BlockedUserListState

    data class Content(val users: List<BlockedUser>) : BlockedUserListState
}
