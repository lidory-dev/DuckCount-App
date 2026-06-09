package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.GroupWithMembers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GroupSettingsViewModel(
    private val groupUuid: String,
    private val groupSettingsCallBacks: GroupSettingsCallBacks,
) : ViewModel() {
    fun getUserFullName(userUuid: String): Flow<String?> {
        return groupSettingsCallBacks.getUserFullName(userUuid)
    }

    private val _state = MutableStateFlow(GroupSettingsState())

    val state: StateFlow<GroupSettingsState> = _state.onStart {
        groupSettingsCallBacks.getGroupWithMembers(groupUuid = groupUuid).onEach { groupWithMembers ->
            _state.update {
                it.copy(
                    groupWithMembers = groupWithMembers,
                    membersWithFullName = groupWithMembers.members.filter { it.userUuid.isNotBlank() }.map { member ->

                        GroupMemberWithFullName(
                            member = member,
                            fullName = getUserFullName(member.userUuid).first() ?: member.userUuid,
                        )
                    },
                )
            }
        }.launchIn(viewModelScope)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = GroupSettingsState(),
    )

    fun removeGroupMember(userUuid: String) {
        viewModelScope.launch {
            groupSettingsCallBacks.removeGroupMember(userUuid, groupUuid)
        }
    }
}

data class GroupSettingsState(
    val groupWithMembers: GroupWithMembers? = null,
    val membersWithFullName: List<GroupMemberWithFullName> = emptyList(),
)
