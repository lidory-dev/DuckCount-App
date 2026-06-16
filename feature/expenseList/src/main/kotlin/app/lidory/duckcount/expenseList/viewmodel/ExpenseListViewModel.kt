package app.lidory.duckcount.expenseList.viewmodel

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = _groupUuid.flatMapLatest { uuid ->
        if (uuid.isNullOrBlank()) {
            expenseListCallBacks.getAllExpensesWithoutGroup()
        } else {
            expenseListCallBacks.getAllExpensesFromGroup(uuid)
        }
            .map { expenses -> ExpenseListScreenState(expenses = expenses) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = ExpenseListScreenState(),
            )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    fun getUserFullName(userUuid: String): Flow<String?> {
        return expenseListCallBacks.getUserFullName(userUuid)
    }
}

data class ExpenseListScreenState(
    val isLoading: Boolean = false,
    val expenses: List<ExpenseWithBeneficiaries> = emptyList(),
)

@Composable
fun expenseListViewModel(groupUuid: String? = null): ExpenseListViewModel =
    koinViewModel(parameters = { parametersOf(groupUuid) })
