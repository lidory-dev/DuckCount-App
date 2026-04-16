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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.checkbox.DuckCountCheckBox
import app.lidory.duckcount.android.ui.composable.checkbox.DuckCountTriStateCheckBox
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
import viewmodel.CreateExpenseViewModel
import viewmodel.toToggleableState
import app.lidory.duckcount.android.ui.R as SharedR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeneficiariesDialog(
    viewModel: CreateExpenseViewModel,
    isDialogShown: Boolean,
    onDismissRequest: () -> Unit,
    onSelectUsers: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
) {
    val usersState by viewModel.usersState.collectAsStateWithLifecycle()
    val beneficiaryListState by viewModel.beneficiaryListState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    LaunchedEffect(viewModel.searchQuery) {
        listState.animateScrollToItem(0)
    }

    FullScreenDialog(
        onDismissRequest = {
            onDismissRequest()
            viewModel.clearSearchQuery()
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
                                viewModel.clearSearchQuery()
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
                        searchQuery = viewModel.searchQuery,
                        onSearchChange = { viewModel.onSearchQueryChange(query = it) },
                        focusManager = focusManager,
                    )

                    if (usersState.users.isEmpty()) {
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
                                    visible = viewModel.searchQuery.isBlank(),
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
                                                        viewModel.toggleSelectAllUsers()
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
                                                DuckCountTriStateCheckBox(
                                                    state = beneficiaryListState.toToggleableState(),
                                                    onClick = {
                                                        viewModel.toggleSelectAllUsers()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            items(
                                items = usersState.users,
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
                                        viewModel.toggleSelectedUser(user = user)
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
                                        DuckCountCheckBox(
                                            checked = beneficiaryListState.selectedUuids.contains(user.uuid),
                                            onCheckedChange = {
                                                viewModel.toggleSelectedUser(user = user)
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
                        onSelectUsers(viewModel.getAllSelectedUsersUuids())
                        onDismissRequest()
                    },
                    enabled = beneficiaryListState.selectedUuids.isNotEmpty(),
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