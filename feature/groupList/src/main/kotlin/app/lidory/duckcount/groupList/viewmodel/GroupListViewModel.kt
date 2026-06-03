package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Group
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class GroupListViewModel(
    val groupListCallBacks: GroupListCallBacks,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupListState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = _state.flatMapLatest {
        groupListCallBacks.getAllGroups()
    }
        .map { groups -> GroupListState(groups = groups) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = _state.value,
        )
}

data class GroupListState(
    val isLoading: Boolean = false,
    val groups: List<Group> = emptyList(),
)
