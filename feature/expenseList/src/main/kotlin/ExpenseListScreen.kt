import androidx.compose.foundation.clickable
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import app.lidory.duckcount.android.ui.constant.Padding
import component.ExpenseCard
import component.StickyHeaderElevated
import kotlinx.datetime.LocalDateTime
import org.koin.androidx.compose.koinViewModel
import viewmodel.ExpenseListViewModel
import kotlin.uuid.ExperimentalUuidApi

// TODO: Add pull-to-refresh.
@OptIn(ExperimentalUuidApi::class)
@Composable
fun ExpenseListScreen(
    modifier: Modifier = Modifier,
    onExpenseClicked: (String) -> Unit,
) {
    val viewModel = koinViewModel<ExpenseListViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The groupBy convert the timestamp on LocalDateTime object during grouping
    val groupedExpenseList = state.expenses.groupBy { LocalDateTime.parse(it.timestamp) }

    LazyColumn(
        modifier = modifier
            .clipScrollableContainer(orientation = Orientation.Vertical)
            .padding(all = Padding.medium),
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

                // We navigate when clicking on an expense, so we need to safeguard the onClick with dropUnlessResumed.
                ExpenseCard(
                    modifier = Modifier
                        .clickable(
                            onClick = dropUnlessResumed { onExpenseClicked(expense.uuid) },
                        )
                        .padding(horizontal = Padding.medium, vertical = Padding.small),
                    title = expense.title,
                    amount = expense.amount,
                )
            }

            item {
                Spacer(modifier = Modifier.padding(bottom = Padding.large))
            }
        }
    }
}
