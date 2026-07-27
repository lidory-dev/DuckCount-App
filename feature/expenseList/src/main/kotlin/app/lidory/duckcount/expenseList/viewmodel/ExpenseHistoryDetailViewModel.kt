package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.PreviousExpenseUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseHistoryDetailViewModel(
    groupUuid: String?,
    private val expenseIndex: Int,
    private val expenseUuid: String,
    private val expenseHistoryCallBacks: ExpenseHistoryCallBacks,
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
            val currency = expenseHistoryCallBacks.getCurrency(expenseUuid).first()

            if (expensesValue.getOrNull(expenseIndex) == null || currency == null) {
                ExpenseHistoryDetailState.NotFound
            } else {
                ExpenseHistoryDetailState.Content(
                    expense = expensesValue[expenseIndex],
                    currency = currency,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseHistoryDetailState.Loading,
        )

    fun isFirstEntry(): Boolean = expenseIndex == expenses.value.lastIndex

    fun getUserFullName(userUuid: String): Flow<String?> = expenseHistoryCallBacks.getUserFullName(userUuid)
}

sealed interface ExpenseHistoryDetailState {
    data object Loading : ExpenseHistoryDetailState
    data object NotFound : ExpenseHistoryDetailState
    class Content(
        val expense: PreviousExpenseUi,
        val currency: Currency,
    ) : ExpenseHistoryDetailState
}
