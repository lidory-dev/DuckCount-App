package viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// TODO: Move this somewhere else.
data class Expense(
    val uuid: String,
    val timestamp: String,
)

class ExpenseDetailViewModel(
    expenseUuid: String,
    expenseDate: String,
) : ViewModel() {
    private val _expenseState = MutableStateFlow(
        Expense(expenseUuid, expenseDate), // TODO
    )

    val expenseState = _expenseState.asStateFlow()
}
