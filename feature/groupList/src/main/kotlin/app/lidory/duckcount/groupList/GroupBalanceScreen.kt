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
import java.math.BigDecimal

@Composable
fun GroupBalanceScreen(
    modifier: Modifier = Modifier,
    viewModel: GroupBalanceViewModel = koinViewModel(),
    isAppInDarkTheme: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.padding(top = Padding.medium),
    ) {
        val membersWithBalanceSorted =
            state.members
                .sortedByDescending {
                    it.balance.toBigDecimal()
                }
                .filter {
                    it.balance.toBigDecimal() != BigDecimal.ZERO
                }

        items(
            items = membersWithBalanceSorted,
            key = { it.member.member.userUuid },
        ) { member ->
            BalanceGraphItem(
                fullName = member.member.fullName,
                debt = member.balance.toBigDecimal(),
                maxAmount = membersWithBalanceSorted.maxOf { it.balance.toBigDecimal().abs() },
                isAppInDarkTheme = isAppInDarkTheme,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Padding.small, start = Padding.small, end = Padding.small),
            )
        }
    }
}
