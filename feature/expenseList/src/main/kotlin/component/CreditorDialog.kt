package component

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
import org.koin.androidx.compose.koinViewModel
import viewmodel.UserViewModel

@Composable
fun CreditorDialog(
    isDialogShown: Boolean,
    onDismissRequest: () -> Unit,
    onSelectedCreditor: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
) {
    val viewModel = koinViewModel<UserViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val usersList = state.users

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
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Padding.large),
        ) {
            TopBar(
                title = "Auteur de la dépense",
                onBack = onDismissRequest,
            )
            CustomTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(text = "Rechercher") },
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
                    Text(text = "Aucun résultat")
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = filteredUsers,
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
                                onSelectedCreditor(user.fullName)
                                onDismissRequest()
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
                                    modifier = Modifier.padding(TextFieldDefaults.contentPaddingWithLabel()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}