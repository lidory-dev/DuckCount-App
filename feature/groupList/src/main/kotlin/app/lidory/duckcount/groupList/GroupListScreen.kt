package app.lidory.duckcount.groupList

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.EmptyContentContainer
import app.lidory.duckcount.groupList.component.GroupCard
import app.lidory.duckcount.groupList.viewmodel.GroupListState
import app.lidory.duckcount.groupList.viewmodel.GroupListViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun GroupListScreen(
    onGroupClicked: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val viewModel = koinViewModel<GroupListViewModel>()

    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val state = state) {
        GroupListState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is GroupListState.Content -> {
            if (state.groups.isEmpty()) {
                EmptyContentContainer(
                    icon = R.drawable.ic_group,
                    text = R.string.you_are_not_part_of_any_group,
                )
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClick = { onGroupClicked(group.uuid) },
                                ),
                            groupUuid = group.uuid,
                            animatedVisibilityScope = animatedVisibilityScope,
                            sharedTransitionScope = sharedTransitionScope,
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.padding(bottom = 80.dp))
                    }
                }
            }
        }
    }
}
