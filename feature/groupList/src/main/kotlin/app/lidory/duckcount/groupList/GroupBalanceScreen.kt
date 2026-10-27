package app.lidory.duckcount.groupList

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.BalanceGraphItem
import app.lidory.duckcount.groupList.viewmodel.GroupBalanceViewModel
import app.lidory.duckcount.groupList.viewmodel.MembersWithBalanceState
import org.koin.androidx.compose.koinViewModel
import java.math.BigDecimal


@Composable
fun GroupBalanceScreen(
    modifier: Modifier = Modifier,
    viewModel: GroupBalanceViewModel = koinViewModel(),
    isAppInDarkTheme: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val state = state) {
        MembersWithBalanceState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is MembersWithBalanceState.Content -> {
            val membersWithBalanceSorted =
                state.members
                    .sortedByDescending {
                        it.balance.toBigDecimal()
                    }
                    .filterNot {
                        it.balance.toBigDecimal().compareTo(BigDecimal.ZERO) == 0
                    }

            if (membersWithBalanceSorted.isEmpty()) {
                EmptyContentContainer(
                    icon = R.drawable.ic_balance,
                    text = R.string.everything_is_balanced,
                )
            } else {
                LazyColumn(
                    modifier = modifier.padding(top = Padding.medium),
                ) {
                    items(
                        items = membersWithBalanceSorted,
                        key = { it.member.member.userUuid },
                    ) { member ->
                        val fullName = member.member.fullName ?: stringResource(R.string.unknown_member)

                        BalanceGraphItem(
                            fullName = if (member.isFormerMember) {
                                stringResource(R.string.former_group_member, fullName)
                            } else {
                                fullName
                            },
                            debt = member.balance.toBigDecimal(),
                            maxAmount = membersWithBalanceSorted.maxOf { it.balance.toBigDecimal().abs() },
                            currencySymbol = state.currencySymbol,
                            isAppInDarkTheme = isAppInDarkTheme,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Padding.small, start = Padding.small, end = Padding.small),
                        )
                    }
                }
            }
        }
    }
}
