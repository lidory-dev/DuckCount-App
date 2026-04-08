import androidx.compose.foundation.clickable
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
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
    onCreateExpenseClicked: (String) -> Unit,
) {
    val viewModel = koinViewModel<ExpenseListViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The groupBy convert the timestamp on LocalDateTime object during grouping
    val groupedExpenseList = state.expenses.groupBy { LocalDateTime.parse(it.timestamp) }

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {

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
                        // TODO: get fullName from userRepository
                        creditorName = expense.creditorUuid,
                        amount = expense.amount,
                    )
                }
                item {
                    Spacer(modifier = Modifier.padding(bottom = Padding.large))
                }
            }
        }
        FloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            onClick = {
                // TODO: give current connected user
                onCreateExpenseClicked("0")
            },
        ) {
            Icon(painter = painterResource(R.drawable.ic_add), contentDescription = "Add expense")
        }
    }
}
