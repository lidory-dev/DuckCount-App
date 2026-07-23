package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.tab.Tab
import kotlinx.coroutines.launch

@Composable
fun GroupPager(
    pagerState: PagerState,
    tabs: List<Tab>,
    expenseListContent: @Composable () -> Unit,
    balanceContent: @Composable () -> Unit,
    settleUpContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Padding.small),
                    ) {
                        Icon(
                            painter = tab.icon(),
                            contentDescription = tab.title(),
                        )
                        Text(tab.title())
                    }
                },
            )
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
    ) { page ->
        when (page) {
            0 -> expenseListContent()
            1 -> balanceContent()
            2 -> settleUpContent()
        }
    }
}