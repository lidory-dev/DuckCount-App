package app.lidory.duckcount.groupList

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.button.ButtonBottomPadding
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedLoadingButton
import app.lidory.duckcount.android.ui.composable.button.isButtonPinned
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.CurrencyDropDownMenu
import app.lidory.duckcount.groupList.component.GroupMemberFullScreenDialog
import app.lidory.duckcount.groupList.component.groupMemberList.groupMemberList
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupEditError
import app.lidory.duckcount.groupList.model.message
import app.lidory.duckcount.groupList.viewmodel.CreateGroupState
import app.lidory.duckcount.groupList.viewmodel.CreateGroupViewModel
import app.lidory.duckcount.groupList.viewmodel.canConfirm
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateOrEditGroupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateGroupViewModel = koinViewModel(),
) {
    val userListState by viewModel.usersState.collectAsStateWithLifecycle()
    val selectedUuids by viewModel.selectedUuids.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val isGone by viewModel.isGone.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingError by remember { mutableStateOf<GroupCreationError?>(null) }
    var pendingEditError by remember { mutableStateOf<GroupEditError?>(null) }

    // Do not allow navigating back while creating the group, or saving what was edited of it.
    BackHandler(enabled = isSaving) {}

    val isButtonPinned = isButtonPinned()

    val confirmButton: @Composable (content: CreateGroupState.Content, modifier: Modifier) -> Unit =
        { content, modifier ->
            DuckCountElevatedLoadingButton(
                modifier = modifier.fillMaxWidth(),
                enabled = !isSaving
                        && !isSaved
                        && content.canConfirm(
                            maxTitleLength = viewModel.maxTitleLength,
                            maxDescriptionLength = viewModel.maxDescriptionLength,
                        ),
                colors = ButtonDefaults.buttonColors()
                    .copy(containerColor = MaterialTheme.colorScheme.primary),
                onClick = {
                    // Navigation happens via viewModel.isSaved once the group is created or its
                    // edits saved, otherwise popping this screen cancels the write under way.
                    if (viewModel.groupUuid != null) viewModel.updateGroup() else viewModel.createGroup()
                },
                isLoading = isSaving,
            ) {
                Text(
                    modifier = Modifier.padding(Padding.small),
                    text = stringResource(androidUiR.string.confirm),
                )
            }
        }

    // Navigate back only once the group is created or its edits saved (see
    // CreateGroupViewModel.isSaved), or the group being edited is gone (see
    // CreateGroupViewModel.isGone), and on resuming: onBack does nothing until the screen is
    // resumed, so a creation that completes while the app is in the background closes the screen
    // once the user is back.
    LifecycleResumeEffect(isSaved, isGone) {
        if (isSaved || isGone) onBack()

        onPauseOrDispose {}
    }

    // Tell why the creation failed, and who it was about, or why the edit was not saved.
    LaunchedEffect(Unit) {
        viewModel.showError.collect { pendingError = it }
    }

    LaunchedEffect(Unit) {
        viewModel.showEditError.collect { pendingEditError = it }
    }

    pendingError?.let { error ->
        val message = error.message()
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingError = null
        }
    }

    pendingEditError?.let { error ->
        val message = error.message()
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingEditError = null
        }
    }

    GroupMemberFullScreenDialog(
        isDialogShown = viewModel.isMemberDialogShown,
        onSelectUsers = { uuids ->
            viewModel.onMembersSelected(uuids)
            viewModel.hideMemberDialog()
        },
        onDismissRequest = { viewModel.hideMemberDialog() },
        userList = userListState.users,
        nobodyToPick = userListState.nobodyToPick,
        searchQuery = viewModel.searchQuery,
        clearSearchQuery = { viewModel.clearSearchQuery() },
        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
        toggleSelectedUser = { viewModel.toggleSelectedUser(it.uuid) },
        selectedUuids = selectedUuids,
        maxMembers = viewModel.maxMembers,
    )

    Scaffold(
        modifier = modifier.fillMaxWidth(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (viewModel.groupUuid != null)
                            stringResource(R.string.edit_group)
                        else
                            stringResource(R.string.create_a_group),
                    )
                },
                navigationIcon = {
                    IconButton(
                        enabled = !isSaving,
                        onClick = onBack,
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
            // The snackbars above the button, in the bar rather than over the screen: the list
            // ends above the bar, so that a snackbar hides none of its rows, see
            // DuckCountSnackbarHost. The bar above the keyboard, which pushes it up, so that the
            // button stays in reach as on every form: in the bar only while the window has room
            // for it there, see isButtonPinned, at the end of the list otherwise. The keyboard's
            // inset holds the navigation bar's while it is open. As wide as the window whether
            // or not the button is in it, so that a snackbar stays centred, see
            // DuckCountSnackbarHost.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                DuckCountSnackbarHost(hostState = snackbarHostState)

                val content = viewModel.state as? CreateGroupState.Content

                if (content != null && isButtonPinned) {
                    confirmButton(
                        content,
                        Modifier
                            .padding(horizontal = Padding.medium)
                            .padding(bottom = ButtonBottomPadding),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                // Above the bar, and so above the keyboard, which pushes it up: the field being
                // filled is scrolled into the room left above the button, or above the keyboard
                // while the button is at the end of the list, rather than under them. Read as the
                // Box is laid out, so that the bar moving with the keyboard recomposes nothing.
                .padding(innerPadding)
                .padding(horizontal = Padding.medium)
                .fillMaxSize(),
        ) {
            when (val state = viewModel.state) {
                CreateGroupState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is CreateGroupState.Content -> {
                    LazyColumn(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) {
                                focusManager.clearFocus()
                            }
                            .fillMaxSize(),
                        // The last row as far from the button below as the rows are from each
                        // other, or, while the button is that row, it ButtonBottomPadding off the
                        // keyboard, as everywhere.
                        contentPadding = PaddingValues(
                            bottom = if (isButtonPinned) Padding.medium else ButtonBottomPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Padding.medium),
                    ) {
                        item {
                            DuckCountTextField(
                                value = state.title,
                                labelName = stringResource(androidUiR.string.title),
                                enabled = !isSaving,
                                onValueChange = { viewModel.updateTitle(title = it) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                focusManager = focusManager,
                                error = stringResource(androidUiR.string.title_mandatory),
                                isInError = state.title.isBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        viewModel.groupUuid ?: run {
                            item {
                                CurrencyDropDownMenu(
                                    currency = viewModel.currencyDisplayName(state.currency.name),
                                    currencies = viewModel.currencies,
                                    currencyDisplayName = viewModel::currencyDisplayName,
                                    onValueChange = viewModel::updateCurrency,
                                    enabled = !isSaving,
                                )
                            }
                        }

                        item {
                            DuckCountTextField(
                                value = state.description,
                                labelName = stringResource(androidUiR.string.description),
                                enabled = !isSaving,
                                onValueChange = { viewModel.updateDescription(description = it) },
                                singleLine = false,
                                maxLines = 5,
                                // Counted as it is saved, without the whitespace around it, as the
                                // button counts it, see canConfirm.
                                error = stringResource(
                                    R.string.description_cant_exceed_max,
                                    state.trimmedDescription.length,
                                    viewModel.maxDescriptionLength,
                                ),
                                isInError = state.trimmedDescription.length > viewModel.maxDescriptionLength,
                                focusManager = focusManager,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // A group's members are changed from its settings, by its creator alone, see
                        // GroupSettingsScreen.
                        if (viewModel.groupUuid == null) {
                            groupMemberList(
                                members = state.members,
                                maxMembers = viewModel.maxMembers,
                                enabled = !isSaving,
                                authenticatedUserUuid = viewModel.getAuthenticatedUserUuid(),
                                onModifyMemberList = {
                                    viewModel.showMemberDialog()
                                },
                            )
                        }

                        // Out of the bar while the window is too short for it there, see
                        // isButtonPinned.
                        if (!isButtonPinned) {
                            item { confirmButton(state, Modifier) }
                        }
                    }
                }
            }
        }
    }
}
