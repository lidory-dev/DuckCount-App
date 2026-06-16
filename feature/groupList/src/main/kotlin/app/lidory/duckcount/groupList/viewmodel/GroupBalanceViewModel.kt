package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupBalanceCallBacks
import app.lidory.duckcount.groupList.model.GroupMemberWithBalance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class GroupBalanceViewModel(
    private val groupUuid: String,
    groupBalanceCallBacks: GroupBalanceCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(MembersWithBalanceState())

    val state: StateFlow<MembersWithBalanceState> = _state.onStart {
        groupBalanceCallBacks.getAllMembers(groupUuid).onEach { groupMembersWithFullName ->
            val debts =
                groupBalanceCallBacks.getGroupDebt(groupUuid = groupUuid, groupMembersWithFullName.map { it.member })
            _state.update {
                it.copy(
                    members = groupMembersWithFullName.map { memberWithFullName ->
                        GroupMemberWithBalance(
                            member = memberWithFullName,
                            balance = debts[memberWithFullName.member.userUuid]
                                ?: memberWithFullName.member.userUuid, // TODO: Show that the username was not found?
                        )
                    },
                )
            }
        }.launchIn(viewModelScope)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MembersWithBalanceState(),
    )
}

data class MembersWithBalanceState(
    val members: List<GroupMemberWithBalance> = emptyList(),
)
