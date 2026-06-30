package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.PreviousExpenseUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ExpenseHistoryViewModel(
    groupUuid: String?,
    expenseUuid: String,
    expenseHistoryCallBacks: ExpenseHistoryCallBacks,
) : ViewModel() {
    private val expenses: StateFlow<List<PreviousExpenseUi>> =
        expenseHistoryCallBacks.getPreviousExpenses(groupUuid, expenseUuid)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList(),
            )

    val state: StateFlow<ExpenseHistoryState> =
        expenses.map { expensesValue ->
            val currencySymbol = expenseHistoryCallBacks.getCurrency(expenseUuid)
            if (expensesValue.isEmpty()) {
                ExpenseHistoryState.Loading
            } else {
                ExpenseHistoryState.Content(expensesValue, currencySymbol.first()?.symbol ?: "")
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseHistoryState.Loading,
        )
}

sealed interface ExpenseHistoryState {

    data object Loading : ExpenseHistoryState

    data object NotFound : ExpenseHistoryState

    data class Content(
        val expenses: List<PreviousExpenseUi>,
        val currencySymbol: String = "",
    ) : ExpenseHistoryState
}
