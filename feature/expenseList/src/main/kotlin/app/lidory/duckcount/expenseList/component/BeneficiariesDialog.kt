package app.lidory.duckcount.expenseList.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import app.lidory.duckcount.android.ui.composable.button.ButtonBottomPadding
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedButton
import app.lidory.duckcount.android.ui.composable.button.isButtonPinned
import app.lidory.duckcount.android.ui.composable.checkbox.SelectAllPill
import app.lidory.duckcount.android.ui.composable.checkbox.UserSelectableCard
import app.lidory.duckcount.android.ui.composable.fullscreendialog.FullScreenDialog
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeneficiariesDialog(
    isDialogShown: Boolean,
    onDismissRequest: () -> Unit,
    onSelectUsers: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
    usersList: List<User>,
    searchQuery: String,
    clearSearchQuery: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    toggleSelectAllUsers: () -> Unit,
    toggleSelectedUser: (User) -> Unit,
    toggleableListState: ToggleableState,
    /**
     * Whether to offer "Select all", which is for a group's members: a one-time expense shows how
     * many are selected against the limit in its place.
     */
    isSelectAllShown: Boolean,
    selectedUuids: Set<String>,
    maxBeneficiaries: Int,
) {

    val listState = rememberLazyListState()

    val isButtonPinned = isButtonPinned()

    val confirmButton: @Composable (Modifier) -> Unit = { modifier ->
        DuckCountElevatedButton(
            modifier = modifier.fillMaxWidth(),
            onClick = {
                onSelectUsers(selectedUuids)
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
                        Text(text = stringResource(R.string.select_beneficiaries))
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
            bottomBar = {
                // As on the expense form, see CreateExpenseScreen: the bar above the keyboard, which
                // pushes it up, the button in it only while the window has room for it there, see
                // isButtonPinned, at the end of the list otherwise.
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                    if (isButtonPinned) {
                        confirmButton(
                            Modifier
                                .padding(horizontal = Padding.medium)
                                .padding(bottom = ButtonBottomPadding),
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Above the bar, and so above the keyboard, read as the column is laid out, as
                    // on the expense form.
                    .padding(innerPadding)
                    .padding(horizontal = Padding.medium),
                verticalArrangement = Arrangement.spacedBy(Padding.large),
            ) {
                DuckCountSearchBar(
                    modifier = Modifier.fillMaxWidth(),
                    searchQuery = searchQuery,
                    onSearchChange = { onSearchQueryChange(it) },
                    focusManager = focusManager,
                )

                // Above the list rather than in it, so that it stays in view while scrolling:
                // a group's "Select all", or, for a one-time expense, which has none, the count
                // against the limit in its place, shown while searching too, since the cards
                // not selected are disabled once it is reached.
                if (isSelectAllShown) {
                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.End),
                        visible = searchQuery.isBlank() && usersList.isNotEmpty(),
                    ) {
                        SelectAllPill(
                            toggleSelectAllUsers = toggleSelectAllUsers,
                            toggleableListState = toggleableListState,
                        )
                    }
                } else {
                    Text(
                        modifier = Modifier.align(Alignment.End),
                        text = pluralStringResource(
                            R.plurals.selected_beneficiaries_out_of_limit,
                            selectedUuids.size,
                            selectedUuids.size,
                            maxBeneficiaries,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (usersList.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(text = stringResource(R.string.no_result))
                    }

                    // Out of the bar while the window is too short for it there, see isButtonPinned.
                    if (!isButtonPinned) confirmButton(Modifier)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize(),
                        // The last card clear of the button below, or, while the button ends the
                        // list, it ButtonBottomPadding off the keyboard, as everywhere.
                        contentPadding = PaddingValues(
                            bottom = if (isButtonPinned) Padding.medium else ButtonBottomPadding,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(
                            items = usersList,
                            key = { user -> user.uuid },
                        ) { user ->
                            val isChecked = selectedUuids.contains(user.uuid)

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
                                isChecked = isChecked,
                                // A selected card stays enabled so that it can still be
                                // unselected once the selection is full.
                                enabled = isChecked || selectedUuids.size < maxBeneficiaries,
                            )
                        }

                        // Out of the bar while the window is too short for it there, see
                        // isButtonPinned.
                        if (!isButtonPinned) {
                            item { confirmButton(Modifier.padding(top = Padding.medium)) }
                        }
                    }
                }
            }
        }
    }
}
