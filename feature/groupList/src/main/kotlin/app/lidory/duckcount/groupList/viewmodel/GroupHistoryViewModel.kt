package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.GroupHistory
import app.lidory.duckcount.groupList.model.GroupHistoryError
import app.lidory.duckcount.groupList.model.GroupInformationVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

class GroupHistoryViewModel(
    groupUuid: String,
    private val groupListCallBacks: GroupListCallBacks,
) : ViewModel() {

    // One more for each time the history is read again, see retry.
    private val reads = MutableStateFlow(0)

    /**
     * Read when the screen first asks, and again on [retry]: it comes from the server, and nothing
     * of it is kept, see [GroupListCallBacks.getGroupHistory].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GroupHistoryState> = reads
        .transformLatest {
            emit(GroupHistoryState.Loading)
            emit(groupListCallBacks.getGroupHistory(uuid = groupUuid).toState())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = GroupHistoryState.Loading,
        )

    /** Reads the history again, once it could not be read. */
    fun retry() {
        reads.update { it + 1 }
    }
}

sealed interface GroupHistoryState {

    data object Loading : GroupHistoryState

    /** Nothing to show, for [error], until it is read again, see [GroupHistoryViewModel.retry]. */
    data class Failed(val error: GroupHistoryError) : GroupHistoryState

    /** The versions to show, newest first, see [GroupHistory.Versions]. */
    data class Content(val versions: List<GroupInformationVersion>) : GroupHistoryState
}

private fun GroupHistory.toState(): GroupHistoryState = when (this) {
    is GroupHistory.Versions -> GroupHistoryState.Content(versions = versions)
    is GroupHistory.Unavailable -> GroupHistoryState.Failed(error = error)
}
