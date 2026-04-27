package viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import database.expense.ExpenseRepository
import database.expense.ExpenseWithBeneficiaries
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ExpenseDetailViewModel(
    expenseUuid: String,
    expenseRepository: ExpenseRepository,
) : ViewModel() {
    val state: StateFlow<ExpenseScreenState> = expenseRepository
        .getWithBeneficiaries(uuid = expenseUuid)
        .map { expenses ->
            ExpenseScreenState(
                expenseWithBeneficiaries = expenses,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseScreenState(),
        )
}

data class ExpenseScreenState(
    val isLoading: Boolean = false,
    val expenseWithBeneficiaries: ExpenseWithBeneficiaries? = null, // TODO.
)
