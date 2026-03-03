package viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExpenseListViewModel() : ViewModel() {
    private val _expenseListState = MutableStateFlow(
        listOf(
            Expense(uuid = "1", timestamp = "2025-04-03T14:30:00"),
            Expense(uuid = "2", timestamp = "2025-03-02T14:30:00"),
            Expense(uuid = "3", timestamp = "2026-03-02T17:30:00"),
            Expense(uuid = "4", timestamp = "2026-06-03T14:30:00"),
            Expense(uuid = "5", timestamp = "2025-07-03T14:30:00"),
            Expense(uuid = "6", timestamp = "2025-08-03T14:30:00"),
            Expense(uuid = "7", timestamp = "2026-08-03T14:30:00"),
            Expense(uuid = "8", timestamp = "2026-08-03T14:30:00"),
            Expense(uuid = "9", timestamp = "2026-09-03T14:30:00"),
            Expense(uuid = "10", timestamp = "2025-10-03T14:30:00"),
            Expense(uuid = "11", timestamp = "2026-10-03T14:30:00"),
            Expense(uuid = "12", timestamp = "2026-11-03T14:30:00"),
            Expense(uuid = "13", timestamp = "2026-11-03T14:30:00"),
            Expense(uuid = "14", timestamp = "2026-11-03T14:30:00"),
            Expense(uuid = "15", timestamp = "2026-12-03T14:30:00"),
            Expense(uuid = "16", timestamp = "2026-12-03T14:30:00"),
            Expense(uuid = "17", timestamp = "2026-03-03T14:30:00"),
            Expense(uuid = "18", timestamp = "2026-02-03T14:30:00"),
            Expense(uuid = "19", timestamp = "2026-02-03T14:30:00"),
            Expense(uuid = "20", timestamp = "2026-02-03T14:30:00"),
        ), // TODO
    )

    val expenseListState = _expenseListState.asStateFlow()
}
