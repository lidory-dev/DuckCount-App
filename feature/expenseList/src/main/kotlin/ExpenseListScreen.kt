import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import component.ExpenseCard
import viewmodel.ExpenseListViewModel


@Composable
fun ExpenseListScreen(
    modifier: Modifier = Modifier,
    onExpenseClicked: (String) -> Unit,
) {
    val viewModel = viewModel<ExpenseListViewModel>()

    val expenseListState by viewModel.expenseListState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier,
    ) {
        items(
            items = expenseListState,
            key = { expense -> expense.uuid },
        ) { expense ->
            // We navigate when clicking on an expense, so we need to safeguard the onClick with dropUnlessResumed.
            //TODO Remove, used as preview
            ExpenseCard(
                modifier = Modifier.clickable(
                    onClick = dropUnlessResumed { onExpenseClicked(expense.uuid) },
                ),
                title = "Expense" + expense.uuid,
                amount = "25",
            )
        }
    }
}