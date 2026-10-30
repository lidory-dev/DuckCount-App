package app.lidory.duckcount.expenseList

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedLoadingButton
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.ExpenseDetailActionButtons
import app.lidory.duckcount.expenseList.component.SettledLabel
import app.lidory.duckcount.expenseList.component.UserAmountCard
import app.lidory.duckcount.expenseList.extensions.formatDate
import app.lidory.duckcount.expenseList.extensions.formatTime
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.expenseList.model.messageRes
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
    navigateToExpenseHistory: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isSettling by viewModel.isSettling.collectAsStateWithLifecycle()
    val isClosed by viewModel.isClosed.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingError by remember { mutableStateOf<ExpenseWriteError?>(null) }

    // Leaving would cancel the write that marks the expense, possibly after the server took it.
    BackHandler(enabled = isSettling) {}

    // Close once the expense is deleted or restored (see ExpenseDetailViewModel.isClosed), and
    // on resuming: onBack does nothing until the screen is resumed, so a write that completes
    // while the app is in the background closes the screen once the user is back.
    LifecycleResumeEffect(isClosed) {
        if (isClosed) onBack()

        onPauseOrDispose {}
    }

    // Surface delete, restore and mark failures with a message that matches the actual cause.
    LaunchedEffect(Unit) {
        viewModel.showError.collect { pendingError = it }
    }
    pendingError?.let { error ->
        val message = stringResource(error.messageRes())
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingError = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // TODO: This should not be done like that.
            val content = state as? ExpenseScreenState.Content
            TopAppBar(
                title = {
                    content?.expenseWithBeneficiaries?.timestamp?.let { timestamp ->
                        val date = LocalDateTime.parse(timestamp)
                        Text(
                            text = stringResource(
                                R.string.date,
                                date.date.formatDate(),
                                date.time.formatTime(),
                            ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        enabled = !isSettling,
                        onClick = onBack,
                    ) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_arrow_back),
                            contentDescription = stringResource(androidUiR.string.back),
                        )
                    }
                },
                actions = {
                    content?.let {
                        ExpenseDetailActionButtons(
                            // One write at a time: deleting the expense while its mark is written
                            // would race the mark to the server. None once the screen is closing.
                            enabled = !isSettling && !isClosed,
                            isDeleted = content.expenseWithBeneficiaries.isDeleted,
                            isSettlement = content.expenseWithBeneficiaries.isSettlement,
                            isOneTimeExpense = content.expenseWithBeneficiaries.groupUuid == null,
                            canEdit = content.canEdit,
                            canDelete = content.canDelete,
                            canRestore = content.canRestore,
                            canShowHistory = content.canShowHistory,
                            restore = viewModel::restoreExpense,
                            delete = viewModel::deleteExpense,
                            edit = dropUnlessResumed { navigateToEditExpense() },
                            history = dropUnlessResumed { navigateToExpenseHistory() },
                        )
                    }
                },
            )
        },
        bottomBar = {
            val content = state as? ExpenseScreenState.Content

            if (content?.canSettle == true) {
                val isSettled = content.expenseWithBeneficiaries.isSettled

                DuckCountElevatedLoadingButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Padding.medium)
                        .padding(bottom = Padding.extraLarge),
                    enabled = !isSettling,
                    colors = ButtonDefaults.buttonColors()
                        .copy(containerColor = MaterialTheme.colorScheme.primary),
                    onClick = viewModel::toggleSettled,
                    isLoading = isSettling,
                ) {
                    Text(
                        modifier = Modifier.padding(Padding.small),
                        text = stringResource(
                            if (isSettled) R.string.mark_as_not_settled else R.string.mark_as_settled,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = state) {
                ExpenseScreenState.Loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                ExpenseScreenState.NotFound -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stringResource(androidUiR.string.an_error_occurred))
                    }
                }

                is ExpenseScreenState.Content -> {
                    val expense = state.expenseWithBeneficiaries

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = Padding.medium),
                        verticalArrangement = Arrangement.spacedBy(Padding.small),
                    ) {
                        item {
                            if (expense.isSettlement) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_balance),
                                        contentDescription = null,
                                    )

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Text(
                                        text = stringResource(R.string.settlement),
                                        style = MaterialTheme.typography.headlineSmall,
                                        textDecoration = TextDecoration.Underline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                Text(
                                    text = expense.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    textDecoration = if (expense.isDeleted) TextDecoration.LineThrough else null,
                                )
                            }

                            val description = expense.description
                            if (description.isNotBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (expense.isDeleted) TextDecoration.LineThrough else null,
                                )
                            }

                            if (expense.isSettled) {
                                SettledLabel(
                                    modifier = Modifier.padding(top = Padding.small),
                                    settledAt = expense.settledAt,
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
                                userUuid = expense.creditorUuid,
                            )
                        }

                        item {
                            Text(
                                modifier = Modifier.padding(Padding.small),
                                style = MaterialTheme.typography.titleMedium,
                                text = pluralStringResource(R.plurals.beneficiaries, state.beneficiaries.size),
                            )
                        }

                        items(state.beneficiaries) { beneficiary ->
                            UserAmountCard(
                                userFullName = beneficiary.fullName,
                                amount = beneficiary.share,
                                currency = expense.currency,
                                userUuid = beneficiary.userUuid,
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            DuckCountSnackbarHost(hostState = snackbarHostState)
        }
    }
}
