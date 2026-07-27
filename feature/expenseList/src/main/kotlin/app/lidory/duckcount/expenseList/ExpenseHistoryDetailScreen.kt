package app.lidory.duckcount.expenseList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.UserAmountCard
import app.lidory.duckcount.expenseList.extensions.formatDate
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryDetailState
import app.lidory.duckcount.expenseList.viewmodel.ExpenseHistoryDetailViewModel
import kotlinx.datetime.LocalDateTime
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseHistoryDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: ExpenseHistoryDetailViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // TODO: This should not be done like that.
            val content = state as? ExpenseHistoryDetailState.Content
            TopAppBar(
                title = {
                    content?.expense?.modifiedAt?.let { timestamp ->
                        val date = LocalDateTime.parse(timestamp)
                        Text(
                            text = stringResource(
                                R.string.date,
                                date.date.formatDate(),
                                "${date.time.hour}:${date.time.minute}",
                            ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_arrow_back),
                            contentDescription = stringResource(androidUiR.string.back),
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
                    Text(text = stringResource(androidUiR.string.an_error_occurred))
                }
            }

            is ExpenseHistoryDetailState.Content -> {
                val expense = state.expense
                val layoutDirection = LocalLayoutDirection.current

                val editorFullName =
                    viewModel.getUserFullName(expense.editorUserUuid)
                        .collectAsState(null)
                        .value ?: expense.editorUserUuid

                val previousCreditorFullName =
                    viewModel.getUserFullName(expense.previousCreditorUuid)
                        .collectAsState(null)
                        .value ?: expense.previousCreditorUuid

                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding(),
                        start = innerPadding.calculateStartPadding(layoutDirection) + Padding.medium,
                        end = innerPadding.calculateEndPadding(layoutDirection) + Padding.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Padding.small),
                ) {
                    item {
                        val textRes =
                            if (viewModel.isFirstEntry()) R.string.created_by else androidUiR.string.modified_by
                        Text(
                            text = stringResource(
                                textRes,
                                editorFullName,
                            ),
                            fontStyle = FontStyle.Italic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    item {
                        Text(
                            text = expense.previousTitle,
                            style = MaterialTheme.typography.headlineSmall,
                        )

                        val description = expense.previousDescription
                        if (description.isNotBlank()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    item {
                        Text(
                            modifier = Modifier.padding(Padding.small),
                            style = MaterialTheme.typography.titleMedium,
                            text = stringResource(R.string.paid_by),
                        )
                    }

                    item {
                        UserAmountCard(
                            userFullName = previousCreditorFullName,
                            amount = expense.previousAmount,
                            currency = state.currency,
                            userUuid = expense.previousCreditorUuid,
                        )
                    }

                    item {
                        Text(
                            modifier = Modifier.padding(Padding.small),
                            style = MaterialTheme.typography.titleMedium,
                            text = stringResource(R.string.beneficiaries),
                        )
                    }

                    items(
                        expense.previousBeneficiaries,
                        key = { beneficiary -> beneficiary.userUuid },
                    ) { beneficiary ->
                        UserAmountCard(
                            userFullName = beneficiary.fullName, // TODO?
                            amount = beneficiary.share,
                            currency = state.currency,
                            userUuid = beneficiary.userUuid,
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
