package viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import database.beneficiary.Beneficiary
import database.beneficiary.BeneficiaryRepository
import database.expense.Expense
import database.expense.ExpenseRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

class ExpenseListViewModel(
    private val expenseRepository: ExpenseRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
) : ViewModel() {
    private var observeExpensesJob: Job? = null

    private val _state = MutableStateFlow(ExpenseListScreenState())
    val state = _state.onStart {
        observeExpenses()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    private fun observeExpenses() {
        observeExpensesJob?.cancel()
        observeExpensesJob = expenseRepository
            .getAll()
            .onEach { expenses ->
                _state.update {
                    it.copy(
                        expenses = expenses
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun createExpense(
        expense: Expense,
        beneficiaries: List<Beneficiary>,
    ) = viewModelScope.launch {
        expenseRepository.insert(expense = expense)
        beneficiaries.map { beneficiaryRepository.insert(beneficiary = it) }
    }
}

data class ExpenseListScreenState(
    val isLoading: Boolean = false,
    val expenses: List<Expense> = emptyList()
)
