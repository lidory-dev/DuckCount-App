package app.lidory.duckcount

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.viewmodel.ExpenseDetailViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExpenseDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: ExpenseDetailViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val expenseState by viewModel.expenseState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier,
    ) {
        Text("ExpenseDetailScreen ${expenseState.uuid}")

        Button(
            onClick = onBack,
        ) {
            Text("Go back to ExpenseListScreen")
        }
    }
}
