package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupDebtCallBacks
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class GroupDebtViewModel(
    val groupUuid: String,
    private val groupDebtCallBacks: GroupDebtCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupDebtState())

    val state: StateFlow<GroupDebtState> = _state.onStart {
        groupDebtCallBacks.getAllMembers(groupUuid).onEach { groupMembersWithFullNames ->
            _state.update {
                val transactions = groupDebtCallBacks.getGroupTransactions(
                    groupUuid = groupUuid,
                    groupMembers = groupMembersWithFullNames.map { groupMemberWithFullName -> groupMemberWithFullName.member },
                )
                it.copy(transactions = transactions)
            }
        }.launchIn(viewModelScope)
        _state.update {
            it.copy(
                authenticatedUserUuid = groupDebtCallBacks.getAuthenticatedUserUuid(),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = GroupDebtState(),
    )
}


data class GroupDebtState(
    val transactions: List<TransactionWithFullName> = emptyList(),
    val authenticatedUserUuid: String = "",
)
