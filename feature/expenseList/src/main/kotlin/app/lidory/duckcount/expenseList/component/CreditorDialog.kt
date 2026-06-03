package app.lidory.duckcount.expenseList.component

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.composable.fullscreendialog.FullScreenDialog
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditorDialog(
    isDialogShown: Boolean,
    onDismissRequest: () -> Unit,
    onSelectedCreditor: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
    usersList: List<User>,
    searchQuery: String,
    clearSearchQuery: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
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
            modifier = Modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = stringResource(R.string.select_creditor))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                onDismissRequest()
                                clearSearchQuery()
                            },
                        ) {
                            Icon(
                                painter = painterResource(androidUiR.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = Padding.medium,
                        start = Padding.medium,
                        end = Padding.medium,
                    ),
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
                        modifier = Modifier.fillMaxSize(),
                    ) {
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
                                    onSelectedCreditor(user.uuid)
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
}
