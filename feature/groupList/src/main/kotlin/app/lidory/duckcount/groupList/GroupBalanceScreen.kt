package app.lidory.duckcount.groupList

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.BalanceGraphItem
import app.lidory.duckcount.groupList.viewmodel.GroupBalanceViewModel
import org.koin.androidx.compose.koinViewModel
import kotlin.math.absoluteValue

@Composable
fun GroupBalanceScreen(
    modifier: Modifier = Modifier,
    viewModel: GroupBalanceViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier,
    ) {
        val membersWithBalanceSorted =
            state.members.sortedByDescending { it.balance.toDouble() }.filter { it.balance != "0" }
        items(
            items = membersWithBalanceSorted,
            key = { it.member.member.uuid },
        ) { member ->
            BalanceGraphItem(
                fullName = member.member.fullName,
                debt = member.balance,
                maxAmount = membersWithBalanceSorted.maxOf { it.balance.toDouble().absoluteValue },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Padding.small, start = Padding.small, end = Padding.small),
            )
        }
    }
}