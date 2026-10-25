package app.lidory.duckcount.groupList

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedLoadingButton
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.CurrencyDropDownMenu
import app.lidory.duckcount.groupList.component.GroupMemberFullScreenDialog
import app.lidory.duckcount.groupList.component.groupMemberList.groupMemberList
import app.lidory.duckcount.groupList.model.GroupCreationError
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val userListState by viewModel.usersState.collectAsStateWithLifecycle()
    val selectedUuids by viewModel.selectedUuids.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val isCreated by viewModel.isCreated.collectAsStateWithLifecycle()
    val isGone by viewModel.isGone.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingError by remember { mutableStateOf<GroupCreationError?>(null) }

    // Do not allow navigating back while creating the group.
    BackHandler(enabled = isCreating) {}

    // Navigate back only once the group is created (see CreateGroupViewModel.isCreated), or the
    // group being edited is gone (see CreateGroupViewModel.isGone), and on resuming: onBack does
    // nothing until the screen is resumed, so a creation that completes while the app is in the
    // background closes the screen once the user is back.
    LifecycleResumeEffect(isCreated, isGone) {
        if (isCreated || isGone) onBack()

        onPauseOrDispose {}
    }

    // Tell why the creation failed, and who it was about.
    LaunchedEffect(Unit) {
        viewModel.showError.collect { pendingError = it }
    }

    pendingError?.let { error ->
        val message = error.message()
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingError = null
        }
    }

    GroupMemberFullScreenDialog(
        isDialogShown = viewModel.isMemberDialogShown,
        onSelectUsers = { uuids -> viewModel.onMembersSelected(uuids) },
        onDismissRequest = { viewModel.hideMemberDialog() },
        userList = userListState.users,
        searchQuery = searchQuery,
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
                        enabled = !isCreating,
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
            (state as? CreateGroupState.Content)?.let { content ->
                // The snackbars above the button, in the bar rather than over the screen: the list
                // running under the bar leaves room at its end for the bar's height, so its last
                // rows can be scrolled clear of a snackbar as well as of the button, see
                // DuckCountSnackbarHost.
                Column {
                    DuckCountSnackbarHost(hostState = snackbarHostState)

                    DuckCountElevatedLoadingButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Padding.medium)
                            .padding(bottom = Padding.extraLarge),
                        enabled = !isCreating
                                && !isCreated
                                && content.canConfirm(
                                    maxTitleLength = viewModel.maxTitleLength,
                                    maxDescriptionLength = viewModel.maxDescriptionLength,
                                ),
                        colors = ButtonDefaults.buttonColors()
                            .copy(containerColor = MaterialTheme.colorScheme.primary),
                        onClick = {
                            if (viewModel.groupUuid != null) {
                                viewModel.updateGroup()

                                onBack()
                            } else {
                                // Navigation happens via viewModel.isCreated once the group is,
                                // otherwise popping this screen cancels the creation under way.
                                viewModel.createGroup()
                            }
                        },
                        isLoading = isCreating,
                    ) {
                        Text(
                            modifier = Modifier.padding(Padding.small),
                            text = stringResource(androidUiR.string.confirm),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = Padding.medium,
                    end = Padding.medium,
                )
                // The keyboard's inset taken whole: the padding above leaves out the Scaffold's
                // bottom, the Box running under the button to the screen's bottom.
                .imePadding()
                .fillMaxSize(),
        ) {
            when (val state = state) {
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
                        // The list scrolls under the button, which floats over its end: past the
                        // last member, room enough for them to be scrolled clear of the button, or
                        // of what of it the keyboard leaves uncovered.
                        contentPadding = WindowInsets(bottom = innerPadding.calculateBottomPadding())
                            .exclude(WindowInsets.ime)
                            .add(WindowInsets(bottom = Padding.medium))
                            .asPaddingValues(),
                        verticalArrangement = Arrangement.spacedBy(Padding.medium),
                    ) {
                        item {
                            DuckCountTextField(
                                value = state.title,
                                labelName = stringResource(androidUiR.string.title),
                                enabled = !isCreating,
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
                                    enabled = !isCreating,
                                )
                            }
                        }

                        item {
                            DuckCountTextField(
                                value = state.description,
                                labelName = stringResource(androidUiR.string.description),
                                enabled = !isCreating,
                                onValueChange = { viewModel.updateDescription(description = it) },
                                singleLine = false,
                                maxLines = 5,
                                error = stringResource(
                                    R.string.description_cant_exceed_max,
                                    state.description.length,
                                    viewModel.maxDescriptionLength,
                                ),
                                isInError = state.description.length > viewModel.maxDescriptionLength,
                                focusManager = focusManager,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        groupMemberList(
                            members = state.members,
                            maxMembers = viewModel.maxMembers,
                            enabled = !isCreating,
                            authenticatedUserUuid = viewModel.getAuthenticatedUserUuid(),
                            onModifyMemberList = {
                                viewModel.showMemberDialog()
                            },
                            removeMember = { member ->
                                viewModel.removeMember(member)
                            },
                        )
                    }
                }
            }
        }
    }
}
