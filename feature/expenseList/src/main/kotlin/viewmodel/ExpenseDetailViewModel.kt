package viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import database.expense.ExpenseRepository
import database.expense.ExpenseWithBeneficiaries
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class ExpenseDetailViewModel(
    private val expenseUuid: String,
    private val expenseRepository: ExpenseRepository,
) : ViewModel() {
    private var observeExpenseJob: Job? = null

    private val _state = MutableStateFlow(ExpenseScreenState())
    val state = _state.onStart {
        observeExpense()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    private fun observeExpense() {
        observeExpenseJob?.cancel()
        observeExpenseJob = expenseRepository
            .getWithBeneficiaries(uuid = expenseUuid)
            .onEach { expenseWithBeneficiaries ->
                _state.update {
                    it.copy(
                        expenseWithBeneficiaries = expenseWithBeneficiaries
                    )
                }
            }
            .launchIn(viewModelScope)
    }
}

data class ExpenseScreenState(
    val isLoading: Boolean = false,
    val expenseWithBeneficiaries: ExpenseWithBeneficiaries? = null, // TODO.
)
