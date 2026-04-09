package app.lidory.duckcount.expenseList

import androidx.compose.foundation.clickable
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.ExpenseCard
import app.lidory.duckcount.expenseList.component.StickyHeaderElevated
import app.lidory.duckcount.expenseList.model.ExpenseStatus
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

    // The groupBy convert the timestamp on LocalDate object during grouping.
    val groupedExpenseList = remember(state.expenses) {
        state.expenses
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
        groupedExpenseList.forEach { (date, expenses) ->
            stickyHeader {
                StickyHeaderElevated(
                    modifier = Modifier
                        .padding(bottom = Padding.medium),
                    date = date,
                )
            }

            items(
                items = expenses,
                key = { expense -> expense.uuid },
            ) { expense ->
                val role = when {
                    expense.creditorUuid == state.connectedUserUuid -> ExpenseStatus.CREDITOR
                    expense.beneficiaries.any { it.userUuid == state.connectedUserUuid } -> ExpenseStatus.BENEFICIARY
                    else -> ExpenseStatus.NEUTRAL
                }

                val personalShare = when (role) {
                    ExpenseStatus.CREDITOR ->
                        expense.beneficiaries
                            .filterNot { it.userUuid == state.connectedUserUuid }
                            .sumOf { it.share.toBigDecimal() }
                            .toString()

                    ExpenseStatus.BENEFICIARY ->
                        expense.beneficiaries
                            .first { it.userUuid == state.connectedUserUuid }
                            .share

                    ExpenseStatus.NEUTRAL -> null
                }

                // We navigate when clicking on an expense, so we need to safeguard the onClick with dropUnlessResumed
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
                    totalAmount = expense.amount,
                    currencySymbol = expense.currency.symbol,
                    personalShare = personalShare,
                    role = role,
                    isActive = !expense.isDeleted,
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
