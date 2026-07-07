package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.PreviousExpenseUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    private val currentExpense: StateFlow<PreviousExpenseUi> =
        expenseHistoryCallBacks.getCurrentExpense(expenseUuid)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = PreviousExpenseUi(
                    modifiedAt = "",
                    modifierUserFullName = "",
                    previousTitle = "",
                    previousDescription = "",
                    previousAmount = "",
                    previousTimestamp = "",
                    previousBeneficiaries = emptyList(),
                    previousIsSettlement = false,
                    previousCreditorFullName = "",
                ),
            )

    val state: StateFlow<ExpenseHistoryState> =
        combine(expenses, currentExpense) { expensesValue, currentExpenseValue ->
            val currencySymbol = expenseHistoryCallBacks.getCurrency(expenseUuid)
            if (expensesValue.isEmpty()) {
                ExpenseHistoryState.Loading
            } else {
                ExpenseHistoryState.Content(
                    expensesValue,
                    currentExpenseValue,
                    currencySymbol.first()?.symbol ?: "",
                )
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
        val currentExpense: PreviousExpenseUi,
        val currencySymbol: String = "",
    ) : ExpenseHistoryState
}
