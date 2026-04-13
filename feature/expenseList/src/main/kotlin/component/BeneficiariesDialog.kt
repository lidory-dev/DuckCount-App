package component

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
import database.user.User
import org.koin.androidx.compose.koinViewModel
import viewmodel.UserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeneficiariesDialog(
    isDialogShown: Boolean,
    onDismissRequest: () -> Unit,
    onAddUsers: (List<User>) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
) {
    val viewModel = koinViewModel<UserViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val usersList = state.users

    val selectedUsers = remember { mutableStateListOf<User>() }

    var searchQuery by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    val filteredUsers by remember(searchQuery, usersList) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                usersList
            } else {
                // TODO: Replace with db query
                usersList.filter { item ->
                    item.fullName.contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    LaunchedEffect(searchQuery) {
        listState.animateScrollToItem(0)
    }

    FullScreenDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        isVisible = isDialogShown,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = stringResource(R.string.add_user))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onDismissRequest,
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = Padding.medium),
                    onClick = {
                        onAddUsers(selectedUsers)
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(R.string.add))
                }
            },
            floatingActionButtonPosition = FabPosition.Center,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = innerPadding.calculateTopPadding()),
                verticalArrangement = Arrangement.spacedBy(Padding.large),
            ) {
                CustomTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(text = stringResource(R.string.search)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_search), contentDescription = "Rechercher")
                    },
                    modifier = Modifier
                        .fillMaxWidth(),
                    singleLine = true,
                    error = "",
                    isInError = false,
                    focusManager = focusManager,
                )


                if (filteredUsers.isEmpty()) {
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
                        items(
                            items = filteredUsers,
                            key = { user -> user.uuid },
                        ) { user ->
                            var isUserSelected by remember { mutableStateOf(false) }
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
                                    isUserSelected = !isUserSelected
                                    if (selectedUsers.contains(user)) selectedUsers.remove(user) else selectedUsers.add(
                                        user,
                                    )
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
                                    Checkbox(
                                        checked = isUserSelected,
                                        onCheckedChange = {
                                            isUserSelected = !isUserSelected
                                            if (selectedUsers.contains(user)) selectedUsers.remove(user) else selectedUsers.add(
                                                user,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            // Spacer to place the last element above the FloatingActionButton
                            Spacer(modifier = Modifier.padding(FloatingActionButtonDefaults.LargeIconSize + Padding.medium))
                        }
                    }
                }
            }
        }
    }
}