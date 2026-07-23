package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Group
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class GroupListViewModel(
    groupListCallBacks: GroupListCallBacks,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GroupListState> = groupListCallBacks.getAllGroups()
        .map { groups ->
            GroupListState.Content(
                groups = groups,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = GroupListState.Loading,
        )
}

sealed interface GroupListState {
    data object Loading : GroupListState
    data class Content(
        val groups: List<Group>,
    ) : GroupListState
}
