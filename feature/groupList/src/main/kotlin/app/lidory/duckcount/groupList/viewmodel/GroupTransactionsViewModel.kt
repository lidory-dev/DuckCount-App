package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupDebtCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupTransactionsViewModel(
    val groupUuid: String,
    private val groupDebtCallBacks: GroupDebtCallBacks,
) : ViewModel() {
    val groupMembers: Flow<List<GroupMemberWithFullName>> = groupDebtCallBacks.getAllMembers(groupUuid)

    val groupExpenses: Flow<List<ExpenseWithBeneficiaries>> = groupDebtCallBacks.getAllGroupAtiveExpenses(groupUuid)

    val state: StateFlow<GroupTransactionsState> =
        combine(groupMembers, groupExpenses) { groupMembersWithFullNames, groupExpenses ->
            val transactions = groupDebtCallBacks.getGroupTransactions(
                groupUuid = groupUuid,
                groupMembers = groupMembersWithFullNames.map { memberWithFullName -> memberWithFullName.member },
                groupExpenses = groupExpenses,
            )
            if (transactions.isEmpty())
                GroupTransactionsState.NotFound
            else
                GroupTransactionsState.Content(
                    transactions = transactions,
                    authenticatedUserUuid = groupDebtCallBacks.getAuthenticatedUserUuid(),
                )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = GroupTransactionsState.Loading,
        )

    fun refundExpense(
        creditorUuid: String,
        debtorUuid: String,
        amount: String,
    ) {
        viewModelScope.launch {
            groupDebtCallBacks.refundExpense(
                creditorUuid, debtorUuid, amount, groupUuid,
            )
        }
    }
}

sealed interface GroupTransactionsState {
    data object Loading : GroupTransactionsState
    data object NotFound : GroupTransactionsState
    data class Content(
        val transactions: List<TransactionWithFullName> = emptyList(),
        val authenticatedUserUuid: String = "",
    ) : GroupTransactionsState
}
