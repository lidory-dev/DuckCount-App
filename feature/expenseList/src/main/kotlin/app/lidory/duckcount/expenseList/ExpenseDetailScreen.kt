package app.lidory.duckcount.expenseList

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.expenseList.viewmodel.ExpenseDetailViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExpenseDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: ExpenseDetailViewModel = koinViewModel(),
    onBack: () -> Unit,
    navigateToEditExpense: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = modifier.padding(innerPadding),
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

            if (state.expenseWithBeneficiaries?.isDeleted ?: false) {
                Button(
                    onClick = viewModel::restoreExpense,
                ) {
                    Text("Restore")
                }
            } else {
                Button(
                    onClick = navigateToEditExpense,
                ) {
                    Text("Edit")
                }

                Button(
                    onClick = viewModel::deleteExpense,
                ) {
                    Text("Delete")
                }
            }

            Button(
                onClick = onBack,
            ) {
                Text("Go back to ExpenseListScreen")
            }
        }
    }
}
