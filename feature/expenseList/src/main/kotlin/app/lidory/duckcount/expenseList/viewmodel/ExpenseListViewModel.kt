package app.lidory.duckcount.expenseList.viewmodel

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class ExpenseListViewModel(
    private val expenseListCallBacks: ExpenseListCallBacks,
    groupUuid: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpenseListScreenState())

    /** Whether the list is of the one-time expenses rather than of the group [groupUuid]'s. */
    val isOneTime: Boolean = groupUuid.isNullOrBlank()

    /** Paged expenses: the one-time ones, server-synced, or the group [groupUuid]'s. */
    val expenses: Flow<PagingData<ExpenseWithBeneficiaries>> =
        if (groupUuid.isNullOrBlank()) {
            expenseListCallBacks.getOneTimeExpensesPaged()
        } else {
            expenseListCallBacks.getGroupExpensesPaged(groupUuid = groupUuid)
        }.cachedIn(viewModelScope)

    val state = flow {
        emit(ExpenseListScreenState(connectedUserUuid = expenseListCallBacks.getAuthenticatedUserUuid() ?: ""))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    /**
     * The newest one-time expenses came on screen.
     *
     * Only that list: what a member does to an expense of a group is announced whatever is on
     * screen, as a change to a one-time expense is, the expense being wherever it sits in the
     * group's list.
     */
    fun onNewestShown() = expenseListCallBacks.newestExpensesShown()

    /** The newest one-time expenses left the screen. */
    fun onNewestHidden() = expenseListCallBacks.newestExpensesHidden()

    fun getUserFullName(userUuid: String): Flow<String?> {
        return expenseListCallBacks.getUserFullName(userUuid)
    }
}

data class ExpenseListScreenState(
    val isLoading: Boolean = false,
    val connectedUserUuid: String = "",
)

@Composable
fun expenseListViewModel(groupUuid: String? = null): ExpenseListViewModel =
    koinViewModel(parameters = { parametersOf(groupUuid) })
