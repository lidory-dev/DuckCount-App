package app.lidory.duckcount.expenseList.viewmodel

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class ExpenseListViewModel(
    private val expenseListCallBacks: ExpenseListCallBacks,
    groupUuid: String?,
) : ViewModel() {
    private val _groupUuid = MutableStateFlow(groupUuid)

    private val _state = MutableStateFlow(ExpenseListScreenState())

    /**
     * Paged, server-synced one-time expenses.
     * TODO: Group expenses still come through [state] as a plain list.
     */
    val oneTimeExpenses: Flow<PagingData<ExpenseWithBeneficiaries>>? =
        if (groupUuid.isNullOrBlank()) {
            expenseListCallBacks.getOneTimeExpensesPaged().cachedIn(viewModelScope)
        } else {
            null
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = _groupUuid.flatMapLatest { uuid ->
        val connectedUserUuid = expenseListCallBacks.getAuthenticatedUserUuid() ?: ""
        if (uuid.isNullOrBlank()) {
            flowOf(ExpenseListScreenState(connectedUserUuid = connectedUserUuid))
        } else {
            expenseListCallBacks.getAllExpensesFromGroup(uuid).map { expenses ->
                ExpenseListScreenState(
                    expenses = expenses,
                    connectedUserUuid = connectedUserUuid,
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    /**
     * The newest one-time expenses came on screen.
     *
     * Only that list: a group's expenses are never announced by a notification.
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
    val expenses: List<ExpenseWithBeneficiaries> = emptyList(),
    val connectedUserUuid: String = "",
)

@Composable
fun expenseListViewModel(groupUuid: String? = null): ExpenseListViewModel =
    koinViewModel(parameters = { parametersOf(groupUuid) })
