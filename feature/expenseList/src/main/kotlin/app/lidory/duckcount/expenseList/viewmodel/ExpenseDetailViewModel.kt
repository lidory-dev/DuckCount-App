package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExpenseDetailViewModel(
    private val expenseUuid: String,
    private val expenseDetailCallBacks: ExpenseDetailCallBacks,
) : ViewModel() {

    private val _state = MutableStateFlow(ExpenseScreenState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ExpenseScreenState> = _state
        .flatMapLatest {
            expenseDetailCallBacks.getExpenseWithBeneficiaries(uuid = expenseUuid)
        }
        .map { expenses ->
            ExpenseScreenState(expenseWithBeneficiaries = expenses)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseScreenState(),
        )

    fun deleteExpense() {
        viewModelScope.launch {
            expenseDetailCallBacks.deleteExpense(expenseUuid)
        }
    }
}

data class ExpenseScreenState(
    val isLoading: Boolean = false,
    val expenseWithBeneficiaries: ExpenseWithBeneficiaries? = null, // TODO.
)
