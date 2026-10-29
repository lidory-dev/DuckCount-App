package app.lidory.duckcount.groupList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountOutlinedButton
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.GroupVersionCard
import app.lidory.duckcount.groupList.model.message
import app.lidory.duckcount.groupList.viewmodel.GroupHistoryState
import app.lidory.duckcount.groupList.viewmodel.GroupHistoryViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * The versions of a group's title and description its history holds, newest first, the one the
 * group is at now marked as the current one, see [GroupVersionCard]: read from the server as the
 * screen opens, and read again on the user's asking when that could not be done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupHistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.group_history))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_arrow_back),
                            contentDescription = stringResource(androidUiR.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = state) {
            GroupHistoryState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is GroupHistoryState.Failed -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(Padding.medium),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error.message(),
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(Padding.large))

                    DuckCountOutlinedButton(onClick = viewModel::retry) {
                        Text(text = stringResource(R.string.group_history_retry))
                    }
                }
            }

            is GroupHistoryState.Content -> {
                val layoutDirection = LocalLayoutDirection.current

                LazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding(),
                        start = innerPadding.calculateStartPadding(layoutDirection) + Padding.medium,
                        end = innerPadding.calculateEndPadding(layoutDirection) + Padding.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Padding.large),
                ) {
                    items(
                        items = state.versions,
                        key = { version -> version.revision },
                    ) { version ->
                        GroupVersionCard(
                            modifier = Modifier.fillMaxWidth(),
                            version = version,
                        )
                    }
                }
            }
        }
    }
}
