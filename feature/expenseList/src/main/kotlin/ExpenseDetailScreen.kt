import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import viewmodel.ExpenseDetailViewModel

@Composable
fun ExpenseDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: ExpenseDetailViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier,
    ) {
        Text("ExpenseDetailScreen ${state.expenseWithBeneficiaries?.uuid}")

        LazyColumn {
            items(state.expenseWithBeneficiaries?.beneficiaries ?: listOf()) {
                Row {
                    Text(it.userUuid + " ")

                    Text(it.share)
                }
            }
        }

        Button(
            onClick = onBack,
        ) {
            Text("Go back to ExpenseListScreen")
        }
    }
}
