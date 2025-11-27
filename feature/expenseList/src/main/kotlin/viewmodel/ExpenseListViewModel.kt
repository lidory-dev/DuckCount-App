package viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExpenseListViewModel() : ViewModel() {
    private val _expenseListState = MutableStateFlow(
        listOf(Expense("1"), Expense("2"), Expense("3")), // TODO
    )

    val expenseListState = _expenseListState.asStateFlow()
}
