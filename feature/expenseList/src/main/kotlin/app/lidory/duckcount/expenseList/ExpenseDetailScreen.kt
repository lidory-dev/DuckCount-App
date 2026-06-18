package app.lidory.duckcount.expenseList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.ExpenseDetailActionDropDownMenu
import app.lidory.duckcount.expenseList.component.UserAmountCard
import app.lidory.duckcount.expenseList.extensions.formatDate
import app.lidory.duckcount.expenseList.viewmodel.ExpenseDetailViewModel
import app.lidory.duckcount.expenseList.viewmodel.ExpenseScreenState
import kotlinx.datetime.LocalDateTime
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            val content = state as? ExpenseScreenState.Content
            TopAppBar(
                title = {
                    content?.expenseWithBeneficiaries?.timestamp?.let { timestamp ->
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
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    content?.let {
                        ExpenseDetailActionDropDownMenu(
                            isDeleted = content.expenseWithBeneficiaries.isDeleted,
                            restore = viewModel::restoreExpense,
                            delete = viewModel::deleteExpense,
                            edit = dropUnlessResumed { navigateToEditExpense() },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = state) {
            ExpenseScreenState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ExpenseScreenState.NotFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = stringResource(R.string.no_result))
                }
            }

            is ExpenseScreenState.Content -> {
                val expense = state.expenseWithBeneficiaries
                val layoutDirection = LocalLayoutDirection.current

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
                        Text(
                            text = expense.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textDecoration = if (expense.isDeleted) TextDecoration.LineThrough else null,
                        )

                        val description = expense.description
                        if (description.isNotBlank()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (expense.isDeleted) TextDecoration.LineThrough else null,
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
                            userFullName = state.creditorFullName,
                            amount = expense.amount,
                            currency = expense.currency,
                        )
                    }

                    item {
                        Text(
                            modifier = Modifier.padding(Padding.small),
                            style = MaterialTheme.typography.titleMedium,
                            text = stringResource(R.string.beneficiaries),
                        )
                    }

                    items(state.beneficiaries) { beneficiary ->
                        UserAmountCard(
                            userFullName = beneficiary.fullName,
                            amount = beneficiary.share,
                            currency = expense.currency,
                        )
                    }
                }
            }
        }
    }
}
