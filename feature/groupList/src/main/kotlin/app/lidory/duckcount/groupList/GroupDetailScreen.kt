package app.lidory.duckcount.groupList

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.GroupActionDropDownMenu
import app.lidory.duckcount.groupList.component.GroupPager
import app.lidory.duckcount.groupList.component.LeftGroupNotice
import app.lidory.duckcount.groupList.tab.BalanceTab
import app.lidory.duckcount.groupList.tab.ExpenseTab
import app.lidory.duckcount.groupList.tab.SettleUpTab
import app.lidory.duckcount.groupList.viewmodel.GroupDetailViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: GroupDetailViewModel = koinViewModel(),
    onBack: () -> Unit,
    navigateToCreateGroupExpense: () -> Unit,
    navigateToGroupSettings: () -> Unit,
    expenseListContent: @Composable () -> Unit,
    balanceContent: @Composable () -> Unit,
    settleUpContent: @Composable () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val tabs = listOf(ExpenseTab(), BalanceTab(), SettleUpTab())
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                modifier = Modifier.clickable(onClick = navigateToGroupSettings),
                title = {
                    state.groupWithMembers?.let {
                        Column {
                            with(sharedTransitionScope) {
                                Text(
                                    modifier = Modifier.sharedBounds(
                                        sharedContentState = rememberSharedContentState(key = "group_title_${state.groupWithMembers?.uuid}"),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    ),
                                    text = it.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleLarge,
                                )

                                if (it.description.isNotBlank()) {
                                    Text(
                                        modifier = Modifier.sharedBounds(
                                            sharedContentState = rememberSharedContentState(key = "group_description_${state.groupWithMembers?.uuid}"),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        ),
                                        text = it.description,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_arrow_back),
                            contentDescription = stringResource(androidUiR.string.back),
                        )
                    }
                },
                actions = {
                    GroupActionDropDownMenu(navigateToGroupSettings = navigateToGroupSettings)
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                // No expense added to a group the user was removed from, which stays read-only.
                visible = pagerState.currentPage == 0 && state.groupWithMembers?.isLeft == false,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                FloatingActionButton(
                    onClick = navigateToCreateGroupExpense,
                ) {
                    Icon(
                        painter = painterResource(androidUiR.drawable.ic_add),
                        contentDescription = stringResource(androidUiR.string.add),
                    )
                }
            }
        },
    ) { innerPadding ->

        Column(
            modifier = modifier.padding(innerPadding),
        ) {
            if (state.groupWithMembers?.isLeft == true) {
                LeftGroupNotice(modifier = Modifier.padding(horizontal = Padding.medium, vertical = Padding.small))
            }

            GroupPager(
                pagerState = pagerState,
                tabs = tabs,
                expenseListContent = expenseListContent,
                balanceContent = balanceContent,
                settleUpContent = settleUpContent,
            )
        }
    }
}
