package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.GroupWithMembers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class GroupDetailViewModel(
    groupUuid: String,
    groupListCallBacks: GroupListCallBacks,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupState())

    val state: StateFlow<GroupState> = _state.onStart {
        groupListCallBacks.getGroupWithMembers(uuid = groupUuid).onEach { groupWithMembers ->
            _state.update {
                it.copy(groupWithMembers = groupWithMembers)
            }
        }.launchIn(viewModelScope)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = GroupState(),
    )

}

data class GroupState(
    val groupWithMembers: GroupWithMembers? = null,
)