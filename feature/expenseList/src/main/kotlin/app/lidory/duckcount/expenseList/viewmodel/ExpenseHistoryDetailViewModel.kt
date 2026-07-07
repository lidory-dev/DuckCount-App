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

class ExpenseHistoryDetailViewModel(
    groupUuid: String?,
    expenseUuid: String,
    expenseIndex: Int,
    expenseHistoryCallBacks: ExpenseHistoryCallBacks,
) : ViewModel() {
    private val expenses: StateFlow<List<PreviousExpenseUi>> =
        expenseHistoryCallBacks.getPreviousExpenses(groupUuid, expenseUuid)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList(),
            )

    val state: StateFlow<ExpenseHistoryDetailState> =
        expenses.map { expensesValue ->
            val currencySymbol = expenseHistoryCallBacks.getCurrency(expenseUuid)
            if (expensesValue.isEmpty()) {
                ExpenseHistoryDetailState.Loading
            } else {
                ExpenseHistoryDetailState.Content(
                    expensesValue[expenseIndex],
                    currencySymbol.first()?.symbol ?: "",
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseHistoryDetailState.Loading,
        )
}

sealed interface ExpenseHistoryDetailState {

    data object Loading : ExpenseHistoryDetailState

    data object NotFound : ExpenseHistoryDetailState

    data class Content(
        val expense: PreviousExpenseUi,
        val currencySymbol: String = "",
    ) : ExpenseHistoryDetailState
}
