package component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import app.lidory.duckcount.android.ui.composable.checkbox.DuckCountCheckbox
import app.lidory.duckcount.android.ui.composable.checkbox.DuckCountTriStateCheckbox
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
import database.user.User
import app.lidory.duckcount.android.ui.R as SharedR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeneficiariesDialog(
    isDialogShown: Boolean,
    onDismissRequest: () -> Unit,
    onSelectUsers: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
    usersList: List<User>,
    searchQuery: String,
    clearSearchQuery: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    toggleSelectAllUsers: () -> Unit,
    toggleSelectedUser: (User) -> Unit,
    toggleableListState: ToggleableState,
    selectedUuids: Set<String>,
) {

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
                        Text(text = stringResource(R.string.add_beneficiaries))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                onDismissRequest()
                                clearSearchQuery()
                            },
                        ) {
                            Icon(
                                painter = painterResource(SharedR.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.back),
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

                    if (usersList.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(text = stringResource(R.string.no_result))
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
                                        Card(
                                            shape = RoundedCornerShape(Padding.extraLarge),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(Padding.extraLarge))
                                                .clickable(
                                                    onClick = {
                                                        toggleSelectAllUsers()
                                                    },
                                                ),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                            ) {
                                                Text(
                                                    modifier = Modifier.padding(start = Padding.large),
                                                    text = stringResource(R.string.select_all),
                                                )
                                                DuckCountTriStateCheckbox(
                                                    state = toggleableListState,
                                                    onClick = {
                                                        toggleSelectAllUsers()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            items(
                                items = usersList,
                                key = { user -> user.uuid },
                            ) { user ->
                                Card(
                                    shape = RoundedCornerShape(size = Padding.large),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem(
                                            fadeInSpec = tween(200),
                                            placementSpec = tween(200),
                                        )
                                        .padding(vertical = Padding.medium),
                                    onClick = {
                                        toggleSelectedUser(user)
                                    },
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(TextFieldDefaults.MinHeight),
                                    ) {
                                        Text(
                                            text = user.fullName,
                                            modifier = Modifier
                                                .padding(TextFieldDefaults.contentPaddingWithLabel()),
                                        )
                                        DuckCountCheckbox(
                                            checked = selectedUuids.contains(user.uuid),
                                            onCheckedChange = {
                                                toggleSelectedUser(user)
                                            },
                                        )
                                    }
                                }
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
                        onSelectUsers(selectedUuids.map { it })
                        onDismissRequest()
                    },
                    enabled = selectedUuids.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors()
                        .copy(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Text(
                        modifier = Modifier.padding(Padding.small),
                        text = stringResource(SharedR.string.add),
                    )
                }
            }
        }
    }
}