package app.lidory.duckcount.groupList

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.GroupActionDropDownMenu
import app.lidory.duckcount.groupList.component.GroupPager
import app.lidory.duckcount.groupList.tab.BalanceTab
import app.lidory.duckcount.groupList.tab.ExpenseTab
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val tabs = listOf(ExpenseTab(), BalanceTab())
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    state.groupWithMembers?.let {
                        Column(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = navigateToGroupSettings,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                )
                                .padding(Padding.small)
                                .fillMaxWidth(),
                        ) {
                            Text(
                                text = it.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (it.description.isNotBlank())
                                Text(
                                    text = it.description,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
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
                visible = pagerState.currentPage == 0,
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
            GroupPager(
                pagerState = pagerState,
                tabs = tabs,
                expenseListContent = expenseListContent,
                balanceContent = balanceContent,
            )
        }
    }
}