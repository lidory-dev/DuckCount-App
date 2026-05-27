package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseListCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ExpenseListViewModel(
    private val expenseListCallBacks: ExpenseListCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpenseListScreenState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = _state.flatMapLatest {
        expenseListCallBacks
            .getAllExpenses()
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
}

data class ExpenseListScreenState(
    val isLoading: Boolean = false,
    val expenses: List<ExpenseWithBeneficiaries> = emptyList(),
)
