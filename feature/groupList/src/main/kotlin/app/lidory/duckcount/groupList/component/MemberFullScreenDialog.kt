package app.lidory.duckcount.groupList.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import app.lidory.duckcount.android.ui.composable.checkbox.SelectAllPill
import app.lidory.duckcount.android.ui.composable.checkbox.UserSelectableCard
import app.lidory.duckcount.android.ui.composable.fullscreendialog.FullScreenDialog
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.groupList.model.User
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberFullScreenDialog(
    modifier: Modifier = Modifier,
    isDialogShown: Boolean,
    onSelectUsers: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    userList: List<User>,
    searchQuery: String,
    clearSearchQuery: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    toggleSelectAllUsers: () -> Unit,
    toggleSelectedUser: (User) -> Unit,
    toggleableListState: ToggleableState,
    selectedUuids: Set<String>,
) {
    val focusManager = LocalFocusManager.current

    val listState = rememberLazyListState()

    LaunchedEffect(searchQuery) {
        listState.animateScrollToItem(0)
    }

    FullScreenDialog(
        onDismissRequest = {
            onDismissRequest()
            clearSearchQuery()
        },
        modifier = modifier,
        isVisible = isDialogShown,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = stringResource(R.string.select_new_members))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                onDismissRequest()
                            },
                        ) {
                            Icon(
                                painter = painterResource(androidUiR.drawable.ic_arrow_back),
                                contentDescription = stringResource(androidUiR.string.back),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = Padding.medium,
                        start = Padding.medium,
                        end = Padding.medium,
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Padding.large),
                ) {
                    DuckCountSearchBar(
                        modifier = Modifier.fillMaxWidth(),
                        searchQuery = searchQuery,
                        onSearchChange = { onSearchQueryChange(it) },
                        focusManager = focusManager,
                    )

                    if (userList.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(text = stringResource(androidUiR.string.no_result))
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            item {
                                AnimatedVisibility(
                                    visible = searchQuery.isBlank(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        SelectAllPill(
                                            toggleSelectAllUsers = toggleSelectAllUsers,
                                            toggleableListState = toggleableListState,
                                        )
                                    }
                                }
                            }

                            items(
                                items = userList,
                                key = { user -> user.uuid },
                            ) { user ->
                                UserSelectableCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem(
                                            fadeInSpec = tween(200),
                                            placementSpec = tween(200),
                                        )
                                        .padding(vertical = Padding.medium),
                                    toggleSelectedUser = { toggleSelectedUser(user) },
                                    fullName = user.fullName,
                                    isChecked = selectedUuids.contains(user.uuid),
                                )
                            }

                            item {
                                // Spacer to place the last element above the add button
                                Spacer(modifier = Modifier.padding(ButtonDefaults.MinHeight))
                            }
                        }
                    }
                }

                ElevatedButton(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = Padding.extraLarge),
                    onClick = {
                        onSelectUsers(selectedUuids.toList())
                        onDismissRequest()
                    },
                    colors = ButtonDefaults.buttonColors()
                        .copy(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        modifier = Modifier.padding(Padding.small),
                        text = stringResource(androidUiR.string.confirm),
                    )
                }
            }
        }
    }
}
