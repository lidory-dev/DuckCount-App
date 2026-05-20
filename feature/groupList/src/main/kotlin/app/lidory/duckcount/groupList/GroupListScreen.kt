package app.lidory.duckcount.groupList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.R
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.GroupCard
import app.lidory.duckcount.groupList.viewmodel.GroupListViewModel
import org.koin.androidx.compose.koinViewModel


@Composable
fun GroupListScreen() {

    val viewModel = koinViewModel<GroupListViewModel>()

    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.groups.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.no_groups))
        }
    } else {

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = Padding.medium,
                ),
            verticalArrangement = Arrangement.spacedBy(Padding.medium),
        ) {
            item {
                Spacer(modifier = Modifier.padding(Padding.small))
            }

            items(
                items = state.groups,
                key = { it.uuid },
            ) { group ->
                GroupCard(
                    title = group.title,
                    description = group.description,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Spacer(modifier = Modifier.padding(bottom = 80.dp))
            }
        }
    }
}
