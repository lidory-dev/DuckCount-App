package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.PreviousExpenseUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ExpenseHistoryViewModel(
    groupUuid: String?,
    expenseUuid: String,
    private val expenseHistoryCallBacks: ExpenseHistoryCallBacks,
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
            val currency = expenseHistoryCallBacks.getCurrency(expenseUuid).first()

            if (currency == null) {
                ExpenseHistoryState.NotFound
            } else {
                ExpenseHistoryState.Content(
                    expensesValue,
                    currency,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseHistoryState.Loading,
        )

    fun getUserFullName(userUuid: String): Flow<String?> = expenseHistoryCallBacks.getUserFullName(userUuid)
}

sealed interface ExpenseHistoryState {

    data object Loading : ExpenseHistoryState

    data object NotFound : ExpenseHistoryState

    data class Content(
        val expenses: List<PreviousExpenseUi>,
        val currency: Currency,
    ) : ExpenseHistoryState
}
