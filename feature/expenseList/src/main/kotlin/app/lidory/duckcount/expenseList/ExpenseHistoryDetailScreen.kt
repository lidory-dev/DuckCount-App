package app.lidory.duckcount.expenseList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.extensions.formatDateTiny
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryDetailState
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryDetailViewModel
import kotlinx.datetime.LocalDateTime
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseHistoryDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: ExpenseHistoryDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (state is ExpenseHistoryDetailState.Content) {
                        val expense = (state as ExpenseHistoryDetailState.Content).expense
                        val date = LocalDateTime.parse(expense.modifiedAt)
                        val dateText = stringResource(
                            R.string.date,
                            date.date.formatDateTiny(),
                            "${date.time.hour}:${date.time.minute}",
                        )
                        Text(text = dateText)
                    }
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
            ExpenseHistoryDetailState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ExpenseHistoryDetailState.NotFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = stringResource(R.string.no_result))
                }
            }

            is ExpenseHistoryDetailState.Content -> {
                val expense = state.expense
                val currencySymbol = state.currencySymbol

                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(Padding.medium),
                ) {
                    Text(
                        text = expense.previousTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = expense.previousDescription.ifBlank { stringResource(androidUiR.string.no_description) },
                        style = MaterialTheme.typography.bodyMedium,
                    )


                    Spacer(modifier = Modifier.padding(Padding.small))

                    Text(
                        text = stringResource(R.string.paid_by),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = expense.previousCreditorFullName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            modifier = Modifier.wrapContentWidth(),
                            maxLines = 1,
                            text = "${expense.previousAmount} $currencySymbol",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Text(
                        text = stringResource(R.string.beneficiaries),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    expense.previousBeneficiaries.forEach {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth(),
                        ) {
                            Text(
                                text = it.fullName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            // TODO: The currency symbol might be before the amount.
                            Text(
                                text = "${it.share} $currencySymbol",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.padding(Padding.small))

                    Text(
                        text = stringResource(androidUiR.string.modified_by, expense.modifierUserFullName),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}