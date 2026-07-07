package app.lidory.duckcount.expenseList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.ExpenseChangesCard
import app.lidory.duckcount.expenseList.extensions.formatDateTiny
import app.lidory.duckcount.expenseList.model.compareToCurrent
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryState
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryViewModel
import kotlinx.datetime.LocalDateTime
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseHistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: ExpenseHistoryViewModel = koinViewModel(),
    navigateToHistoryDetail: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.expense_history))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = state) {
            ExpenseHistoryState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ExpenseHistoryState.NotFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = stringResource(R.string.no_result))
                }
            }

            is ExpenseHistoryState.Content -> {
                val expenses = state.expenses
                val currentExpense = state.currentExpense
                val layoutDirection = LocalLayoutDirection.current

                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding(),
                        start = innerPadding.calculateStartPadding(layoutDirection) + Padding.medium,
                        end = innerPadding.calculateEndPadding(layoutDirection) + Padding.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Padding.medium),
                ) {
                    items(
                        count = expenses.size,
                    ) { index ->
                        val expense = expenses[index]

                        val date = LocalDateTime.parse(expense.modifiedAt)
                        val dateText = stringResource(
                            androidUiR.string.date,
                            date.date.formatDateTiny(),
                            "${date.time.hour}:${date.time.minute}",
                        )
                        ExpenseChangesCard(
                            modifier = Modifier.fillMaxWidth(),
                            modifiedAt = dateText,
                            fieldChanged = if (index == 0)
                                expense.compareToCurrent(currentExpense)
                            else
                                expense.compareToCurrent(expenses[index - 1]),
                            modifierUserFullName = expenses[index].modifierUserFullName,
                            onClick = {
                                navigateToHistoryDetail(
                                    index,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}