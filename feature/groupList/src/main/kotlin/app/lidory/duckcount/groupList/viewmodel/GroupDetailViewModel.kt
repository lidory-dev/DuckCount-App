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
import kotlinx.coroutines.launch

class GroupDetailViewModel(
    groupUuid: String,
    groupListCallBacks: GroupListCallBacks,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupState())

    init {
        // Once, and not whenever the group is collected again: launched into viewModelScope, what
        // each return to it after a few seconds away started would go on running until it is left.
        groupListCallBacks.getGroupWithMembers(uuid = groupUuid).onEach { groupWithMembers ->
            _state.update {
                it.copy(groupWithMembers = groupWithMembers)
            }
        }.launchIn(viewModelScope)

        groupListCallBacks.getUnreadableExpenseCount(uuid = groupUuid).onEach { count ->
            _state.update {
                it.copy(unreadableExpenseCount = count)
            }
        }.launchIn(viewModelScope)
    }

    val state: StateFlow<GroupState> = _state.onStart {
        // Launched rather than awaited, as the group list's refresh is: the group shows what this
        // device has of it while the server is asked.
        viewModelScope.launch { groupListCallBacks.refreshGroupExpenses(uuid = groupUuid) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = GroupState(),
    )

}

data class GroupState(
    val groupWithMembers: GroupWithMembers? = null,

    /**
     * How many expenses of the group this device cannot read yet, which its list and balances
     * leave out, see [GroupListCallBacks.getUnreadableExpenseCount].
     */
    val unreadableExpenseCount: Int = 0,
)