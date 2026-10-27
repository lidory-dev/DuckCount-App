package app.lidory.duckcount.groupList.component

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedLoadingButton
import app.lidory.duckcount.android.ui.composable.checkbox.UserSelectableCard
import app.lidory.duckcount.android.ui.composable.fullscreendialog.FullScreenDialog
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.groupList.model.User
import app.lidory.duckcount.groupList.viewmodel.isSelectable
import app.lidory.duckcount.groupList.viewmodel.memberCount
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * Picks the members of a group besides the user, from their contacts: [selectedUuids] never holds
 * the user, who is in the group whatever is picked, and counts against [maxMembers] all the same.
 * For a group that exists, it picks the members to add to it among [userList], those who are not
 * in it yet, the [currentMemberCount] it has counting against [maxMembers] just as well.
 *
 * Confirming hands the selection to [onSelectUsers], which closes the picker once done with it:
 * adding members to a group keeps it open while [isConfirming], and tells over it, in
 * [snackbarHostState], why they could not be, for the user to pick differently.
 *
 * No "Select all", as for a one-time expense: the user can have more contacts than a group holds,
 * which selecting them all would go past.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMemberFullScreenDialog(
    modifier: Modifier = Modifier,
    isDialogShown: Boolean,
    onSelectUsers: (Set<String>) -> Unit,
    onDismissRequest: () -> Unit,
    userList: List<User>,
    searchQuery: String,
    clearSearchQuery: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    toggleSelectedUser: (User) -> Unit,
    selectedUuids: Set<String>,
    maxMembers: Int,
    currentMemberCount: Int = 1,
    isConfirmEnabled: Boolean = true,
    isConfirming: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
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
                            enabled = !isConfirming,
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
            bottomBar = {
                // The snackbars above the button, as on the group form, see CreateOrEditGroupScreen.
                Column {
                    snackbarHostState?.let { DuckCountSnackbarHost(hostState = it) }

                    DuckCountElevatedLoadingButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Padding.medium)
                            .padding(bottom = Padding.extraLarge),
                        enabled = isConfirmEnabled && !isConfirming,
                        onClick = { onSelectUsers(selectedUuids) },
                        colors = ButtonDefaults.buttonColors()
                            .copy(containerColor = MaterialTheme.colorScheme.primary),
                        isLoading = isConfirming,
                    ) {
                        Text(
                            modifier = Modifier.padding(Padding.small),
                            text = stringResource(androidUiR.string.confirm),
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = innerPadding.calculateTopPadding(),
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

                // Above the list rather than in it, so that it stays in view while scrolling, and
                // shown while searching too, since the cards not selected are disabled once the
                // limit is reached. Told as the members the group would have rather than as who is
                // picked: those it has count, so a group of ten with nobody picked is at ten already.
                val count = memberCount(selectedUuids = selectedUuids, currentMemberCount = currentMemberCount)

                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = pluralStringResource(
                        R.plurals.group_members_out_of_limit,
                        count,
                        count,
                        maxMembers,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        // The list scrolls under the button, which floats over its end: past
                        // the last card, room enough for it to be scrolled clear of the button.
                        contentPadding = PaddingValues(
                            bottom = innerPadding.calculateBottomPadding() + Padding.medium,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
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
                                enabled = !isConfirming && isSelectable(
                                    uuid = user.uuid,
                                    selectedUuids = selectedUuids,
                                    maxMembers = maxMembers,
                                    currentMemberCount = currentMemberCount,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
