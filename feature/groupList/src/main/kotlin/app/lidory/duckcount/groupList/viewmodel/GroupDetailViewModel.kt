package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Group
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
        groupListCallBacks.getGroup(uuid = groupUuid).onEach { group ->
            _state.update {
                it.copy(group = group)
            }
        }.launchIn(viewModelScope)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = GroupState(),
    )

}

data class GroupState(
    val group: Group? = null,
)