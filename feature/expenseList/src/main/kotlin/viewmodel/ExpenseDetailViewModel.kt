package viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// TODO: Move this somewhere else.
data class Expense(
    val uuid: String,
)

class ExpenseDetailViewModel(
    expenseUuid: String,
) : ViewModel() {
    private val _expenseState = MutableStateFlow(
        Expense(expenseUuid), // TODO
    )

    val expenseState = _expenseState.asStateFlow()
}
