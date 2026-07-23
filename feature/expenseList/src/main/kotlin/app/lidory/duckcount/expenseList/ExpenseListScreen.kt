package app.lidory.duckcount.expenseList

import androidx.compose.foundation.clickable
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.ExpenseCard
import app.lidory.duckcount.expenseList.component.StickyHeaderElevated
import app.lidory.duckcount.expenseList.model.ExpenseStatus
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.viewmodel.ExpenseListViewModel
import app.lidory.duckcount.expenseList.viewmodel.expenseListViewModel
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.ExperimentalUuidApi

// TODO: Add pull-to-refresh.
@OptIn(ExperimentalUuidApi::class)
@Composable
fun ExpenseListScreen(
    modifier: Modifier = Modifier,
    onExpenseClicked: (String, String?) -> Unit,
    viewModel: ExpenseListViewModel = expenseListViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val oneTimeExpenses = viewModel.oneTimeExpenses

    if (oneTimeExpenses != null) {
        PagedOneTimeExpenseList(
            modifier = modifier,
            lazyItems = oneTimeExpenses.collectAsLazyPagingItems(),
            connectedUserUuid = state.connectedUserUuid,
            onExpenseClicked = onExpenseClicked,
            viewModel = viewModel,
        )
    } else {
        // Group tab: plain (non-paged) list.
        // TODO: It should be paged too.
        GroupedExpenseList(
            modifier = modifier,
            expenses = state.expenses,
            connectedUserUuid = state.connectedUserUuid,
            onExpenseClicked = onExpenseClicked,
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun PagedOneTimeExpenseList(
    modifier: Modifier,
    lazyItems: LazyPagingItems<ExpenseWithBeneficiaries>,
    connectedUserUuid: String,
    onExpenseClicked: (String, String?) -> Unit,
    viewModel: ExpenseListViewModel,
) {
    LazyColumn(
        modifier = modifier
            .clipScrollableContainer(orientation = Orientation.Vertical)
            .padding(
                top = Padding.medium,
                start = Padding.medium,
                end = Padding.medium,
            ),
    ) {
        items(
            count = lazyItems.itemCount,
            key = lazyItems.itemKey { it.uuid },
        ) { index ->
            val expense = lazyItems[index] ?: return@items
            val date = LocalDateTime.parse(expense.timestamp).date
            val previousDate = if (index > 0) {
                lazyItems.peek(index - 1)?.let { LocalDateTime.parse(it.timestamp).date }
            } else {
                null
            }

            // Insert a date separator whenever the day changes (list is ordered by timestamp DESC).
            if (previousDate != date) {
                StickyHeaderElevated(
                    modifier = Modifier.padding(bottom = Padding.medium),
                    date = date,
                )
            }

            ExpenseListItem(
                expense = expense,
                connectedUserUuid = connectedUserUuid,
                onExpenseClicked = onExpenseClicked,
                viewModel = viewModel,
            )
        }

        item {
            Spacer(modifier = Modifier.padding(bottom = 70.dp))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun GroupedExpenseList(
    modifier: Modifier,
    expenses: List<ExpenseWithBeneficiaries>,
    connectedUserUuid: String,
    onExpenseClicked: (String, String?) -> Unit,
    viewModel: ExpenseListViewModel,
) {
    // The groupBy convert the timestamp on LocalDate object during grouping.
    val groupedExpenseList = remember(expenses) {
        expenses
            .sortedByDescending { it.timestamp }
            .groupBy { LocalDateTime.parse(it.timestamp).date }
    }

    LazyColumn(
        modifier = modifier
            .clipScrollableContainer(orientation = Orientation.Vertical)
            .padding(
                top = Padding.medium,
                start = Padding.medium,
                end = Padding.medium,
            ),
    ) {
        groupedExpenseList.forEach { (date, expensesForDate) ->
            stickyHeader {
                StickyHeaderElevated(
                    modifier = Modifier
                        .padding(bottom = Padding.medium),
                    date = date,
                )
            }

            items(
                items = expensesForDate,
                key = { expense -> expense.uuid },
            ) { expense ->
                ExpenseListItem(
                    expense = expense,
                    connectedUserUuid = connectedUserUuid,
                    onExpenseClicked = onExpenseClicked,
                    viewModel = viewModel,
                )
            }

            item {
                Spacer(modifier = Modifier.padding(bottom = Padding.large))
            }
        }

        item {
            Spacer(modifier = Modifier.padding(bottom = 70.dp))
        }
    }
}

@Composable
private fun ExpenseListItem(
    expense: ExpenseWithBeneficiaries,
    connectedUserUuid: String,
    onExpenseClicked: (String, String?) -> Unit,
    viewModel: ExpenseListViewModel,
) {
    val role = when {
        expense.creditorUuid == connectedUserUuid -> ExpenseStatus.CREDITOR
        expense.beneficiaries.any { it.userUuid == connectedUserUuid } -> ExpenseStatus.BENEFICIARY
        else -> ExpenseStatus.NEUTRAL
    }

    val personalShare = when (role) {
        ExpenseStatus.CREDITOR ->
            expense.beneficiaries
                .filterNot { it.userUuid == connectedUserUuid }
                .sumOf { it.share.toBigDecimal() }
                .toString()

        ExpenseStatus.BENEFICIARY ->
            expense.beneficiaries
                .first { it.userUuid == connectedUserUuid }
                .share

        ExpenseStatus.NEUTRAL -> null
    }

    // In case of a settlement.
    val debtorUuid = if (expense.isSettlement) expense.beneficiaries.first().userUuid else ""

    // We navigate when clicking on an expense, so we need to safeguard the onClick with dropUnlessResumed.
    ExpenseCard(
        modifier = Modifier
            .clickable(
                onClick = dropUnlessResumed { onExpenseClicked(expense.uuid, expense.groupUuid) },
            )
            .padding(horizontal = Padding.medium, vertical = Padding.small),
        title = expense.title,
        creditorName = viewModel.getUserFullName(expense.creditorUuid)
            .collectAsStateWithLifecycle("").value
            ?: expense.creditorUuid, // TODO: Show that the username was not found?
        debtorName = viewModel.getUserFullName(debtorUuid)
            .collectAsStateWithLifecycle("").value
            ?: debtorUuid,
        totalAmount = expense.amount,
        currencySymbol = expense.currency.symbol,
        personalShare = personalShare,
        role = role,
        isActive = !expense.isDeleted,
        isSettlement = expense.isSettlement,
    )
}
