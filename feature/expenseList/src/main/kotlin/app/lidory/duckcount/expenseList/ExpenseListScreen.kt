package app.lidory.duckcount.expenseList

import androidx.compose.foundation.clickable
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.ExpenseCard
import app.lidory.duckcount.expenseList.component.StickyHeaderElevated
import app.lidory.duckcount.expenseList.model.ExpenseStatus
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.viewmodel.ExpenseListViewModel
import app.lidory.duckcount.expenseList.viewmodel.expenseListViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.ExperimentalUuidApi
import app.lidory.duckcount.android.ui.R as androidUiR

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
    val listState = rememberLazyListState()

    // The first item is the newest day's header, which stays first when an expense arrives:
    // see its key below.
    //
    // A value rather than a delegate, so the effect below takes down what it put up. Through
    // a delegate, its clean-up would read the value as it is by then, which is the opposite.
    val showsNewest = remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }.value

    // Only while the newest expenses are on screen, since that is where an arriving one goes
    // in. Scrolled down, it goes in out of sight, and a notification is how the user learns
    // of it.
    LifecycleResumeEffect(viewModel, showsNewest) {
        if (showsNewest) viewModel.onNewestShown()

        onPauseOrDispose { if (showsNewest) viewModel.onNewestHidden() }
    }

    when {
        lazyItems.itemCount > 0 -> LazyColumn(
            state = listState,
            modifier = modifier
                .clipScrollableContainer(orientation = Orientation.Vertical)
                .padding(
                    top = Padding.medium,
                    start = Padding.medium,
                    end = Padding.medium,
                ),
        ) {
            // Walked one by one rather than through `items`, because a day's header has to be
            // an item of its own to stick. Drawn as part of the day's first expense, it
            // scrolled away with it.
            var previousDate: LocalDate? = null

            for (index in 0 until lazyItems.itemCount) {
                val expense = lazyItems.peek(index) ?: continue
                val date = LocalDateTime.parse(expense.timestamp).date

                // A header whenever the day changes: the list is ordered by timestamp DESC,
                // so a day's expenses sit together.
                if (previousDate != date) {
                    if (previousDate != null) {
                        item(key = "gap:$date") {
                            Spacer(modifier = Modifier.padding(bottom = Padding.large))
                        }
                    }

                    // The newest day's header is keyed by its place rather than its date. A
                    // lazy list keeps the item at its top in place, by key, so keyed by date,
                    // an expense from a new day would go in above somebody looking at the
                    // newest, out of sight. Keyed like this, the item at their top is still
                    // the first one, whatever day arrives. Somebody who scrolled down is kept
                    // on the expense they were reading, as they should be.
                    stickyHeader(key = if (previousDate == null) "date:newest" else "date:$date") {
                        StickyHeaderElevated(
                            modifier = Modifier.padding(bottom = Padding.medium),
                            date = date,
                        )
                    }
                }

                previousDate = date

                item(key = expense.uuid) {
                    // Read with `get` rather than the `peek` above: reading an item this way
                    // is what tells Paging where the user is, and so when to load more.
                    lazyItems[index]?.let {
                        ExpenseListItem(
                            expense = it,
                            connectedUserUuid = connectedUserUuid,
                            onExpenseClicked = onExpenseClicked,
                            viewModel = viewModel,
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.padding(bottom = 70.dp))
            }
        }

        !lazyItems.loadState.isSettled -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        else -> EmptyContentContainer(
            icon = androidUiR.drawable.ic_receipt_long,
            text = R.string.no_expenses_yet,
        )
    }
}

/**
 * Whether an empty list means there are no expenses, rather than none read yet.
 *
 * The copy on the device takes a moment to read, and saying "no expenses yet" in the meantime
 * tells somebody with a hundred of them that they have none. When that copy is empty, the
 * server has to have answered too: that is somebody who just signed in, and their expenses
 * are on the way.
 */
private val CombinedLoadStates.isSettled: Boolean
    get() = source.refresh is LoadState.NotLoading && mediator?.refresh !is LoadState.Loading

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

    if (expenses.isEmpty()) {
        EmptyContentContainer(
            icon = androidUiR.drawable.ic_receipt_long,
            text = R.string.no_expenses_yet,
        )
    } else {
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
        // A one-time expense can name somebody this device holds no profile for: the server
        // asks for no relationship between a creditor and the people they name, and a
        // contact who was removed keeps their expenses. A uuid in place of a name says
        // nothing to the person reading it.
        creditorName = viewModel.getUserFullName(expense.creditorUuid)
            .collectAsStateWithLifecycle("").value
            ?: stringResource(R.string.unknown_person),
        debtorName = viewModel.getUserFullName(debtorUuid)
            .collectAsStateWithLifecycle("").value
            ?: stringResource(R.string.unknown_person),
        totalAmount = expense.amount,
        currencySymbol = expense.currency.symbol,
        personalShare = personalShare,
        role = role,
        isActive = !expense.isDeleted,
        isSettlement = expense.isSettlement,
    )
}
