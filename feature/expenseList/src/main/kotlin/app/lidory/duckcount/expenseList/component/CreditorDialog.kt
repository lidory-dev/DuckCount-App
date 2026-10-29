package app.lidory.duckcount.expenseList.component

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.composable.fullscreendialog.FullScreenDialog
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
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
                    // Above the navigation bar and the keyboard, as on the other pickers: the
                    // people a search finds can all be scrolled into view, and what is told when
                    // it finds nobody is centred in what the keyboard leaves.
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
                    .padding(start = Padding.medium, end = Padding.medium, bottom = Padding.medium),
                verticalArrangement = Arrangement.spacedBy(Padding.large),
            ) {
                DuckCountSearchBar(
                    modifier = Modifier.fillMaxWidth(),
                    searchQuery = searchQuery,
                    onSearchChange = { onSearchQueryChange(it) },
                    focusManager = focusManager,
                )

                if (usersList.isEmpty()) {
                    // Only ever a search that found nobody: the user is listed whatever they have.
                    // Centred, as the contacts tab tells it.
                    EmptyContentContainer(
                        icon = androidUiR.drawable.ic_search,
                        text = R.string.no_result,
                    )
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
                                // Their picture on the left, as the contacts tab shows them.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(TextFieldDefaults.MinHeight),
                                ) {
                                    ProfilePictureCard(
                                        modifier = Modifier.fillMaxHeight(),
                                        // TODO: use profile picture
                                        profilePicture = null,
                                        uuid = user.uuid,
                                        fullName = user.fullName,
                                    )

                                    Text(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = Padding.medium),
                                        text = user.fullName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
