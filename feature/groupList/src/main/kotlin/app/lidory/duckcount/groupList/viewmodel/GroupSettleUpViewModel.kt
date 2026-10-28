package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupSettleUpCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.Transaction
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupSettleUpViewModel(
    val groupUuid: String,
    private val groupSettleUpCallBacks: GroupSettleUpCallBacks,
) : ViewModel() {
    val groupMembers: Flow<List<GroupMember>> = groupSettleUpCallBacks.getAllMembers(groupUuid)

    val groupExpenses: Flow<List<ExpenseWithBeneficiaries>> =
        groupSettleUpCallBacks.getAllGroupActiveExpenses(groupUuid)

    // The transactions are worked out again as the members or the expenses change, and named anew
    // as a name does, which takes no working out.
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GroupSettleUpState> =
        combine(
            groupMembers,
            groupExpenses,
            groupSettleUpCallBacks.isGroupLeft(groupUuid),
        ) { groupMembers, groupExpenses, isLeft ->
            val transactions = groupSettleUpCallBacks.getGroupTransactions(
                groupUuid = groupUuid,
                groupMembers = groupMembers,
                groupExpenses = groupExpenses,
            )

            namedTransactions(
                transactions = transactions,
                nameOf = groupSettleUpCallBacks::getDisplayName,
            ).map { named ->
                GroupSettleUpState.Content(
                    transactions = named,
                    authenticatedUserUuid = groupSettleUpCallBacks.getAuthenticatedUserUuid(),
                    // A settlement is an expense, which a group the user was removed from takes no more of.
                    canSettle = !isLeft,
                )
            }
        }.flatMapLatest { content -> content }.stateIn(
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
        val canSettle: Boolean,
    ) : GroupSettleUpState
}

/**
 * Emits [transactions] with the names of their debtors and creditors, as [nameOf] tells them, see
 * [displayNames], each time one changes. A former member of the group, whom the expenses still name,
 * keeps theirs, see [GroupSettleUpCallBacks.getDisplayName].
 */
fun namedTransactions(
    transactions: List<Transaction>,
    nameOf: (userUuid: String) -> Flow<String?>,
): Flow<List<TransactionWithFullName>> =
    displayNames(
        userUuids = transactions.flatMap { listOf(it.debtorUuid, it.creditorUuid) },
        nameOf = nameOf,
    ).map { names ->
        transactions.map { transaction ->
            TransactionWithFullName(
                debtorUuid = transaction.debtorUuid,
                debtorFullName = names[transaction.debtorUuid],
                creditorUuid = transaction.creditorUuid,
                creditorFullName = names[transaction.creditorUuid],
                amount = transaction.amount,
            )
        }
    }
