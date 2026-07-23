package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupSettleUpCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupSettleUpViewModel(
    val groupUuid: String,
    private val groupSettleUpCallBacks: GroupSettleUpCallBacks,
) : ViewModel() {
    val groupMembers: Flow<List<GroupMemberWithFullName>> = groupSettleUpCallBacks.getAllMembers(groupUuid)

    val groupExpenses: Flow<List<ExpenseWithBeneficiaries>> =
        groupSettleUpCallBacks.getAllGroupActiveExpenses(groupUuid)

    val state: StateFlow<GroupSettleUpState> =
        combine(groupMembers, groupExpenses) { groupMembersWithFullNames, groupExpenses ->
            val transactions = groupSettleUpCallBacks.getGroupTransactions(
                groupUuid = groupUuid,
                groupMembers = groupMembersWithFullNames.map { memberWithFullName -> memberWithFullName.member },
                groupExpenses = groupExpenses,
            )

            GroupSettleUpState.Content(
                transactions = transactions,
                authenticatedUserUuid = groupSettleUpCallBacks.getAuthenticatedUserUuid(),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = GroupSettleUpState.Loading,
        )

    fun validateSettlement(
        creditorUuid: String,
        debtorUuid: String,
        amount: String,
    ) {
        viewModelScope.launch {
            groupSettleUpCallBacks.validateSettlement(
                creditorUuid, debtorUuid, amount, groupUuid,
            )
        }
    }
}

sealed interface GroupSettleUpState {
    data object Loading : GroupSettleUpState
    data class Content(
        val transactions: List<TransactionWithFullName>,
        val authenticatedUserUuid: String,
    ) : GroupSettleUpState
}
