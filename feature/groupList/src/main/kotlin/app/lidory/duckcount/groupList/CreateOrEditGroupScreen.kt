package app.lidory.duckcount.groupList

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.CurrencyDropDownMenu
import app.lidory.duckcount.groupList.component.GroupMemberFullScreenDialog
import app.lidory.duckcount.groupList.component.groupMemberList.groupMemberList
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.viewmodel.CreateGroupState
import app.lidory.duckcount.groupList.viewmodel.CreateGroupViewModel
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
    val selectionToggleState by viewModel.selectionToggleState.collectAsStateWithLifecycle()
    val selectedUuids by viewModel.selectedUuids.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    GroupMemberFullScreenDialog(
        isDialogShown = viewModel.isMemberDialogShown,
        onSelectUsers = { uuids ->
            viewModel.updateMembers(
                uuids.map {
                    GroupMember(userUuid = it, groupUuid = "", isAdmin = false)
                },
            )
        },
        onDismissRequest = { viewModel.hideMemberDialog() },
        userList = userListState.users,
        searchQuery = searchQuery,
        clearSearchQuery = { viewModel.clearSearchQuery() },
        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
        toggleSelectAllUsers = { viewModel.toggleSelectAllMembers() },
        toggleSelectedUser = { viewModel.toggleSelectedUser(it.uuid) },
        toggleableListState = selectionToggleState,
        selectedUuids = selectedUuids,
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = Padding.medium,
                    start = Padding.medium,
                    end = Padding.medium,
                )
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
                        verticalArrangement = Arrangement.spacedBy(Padding.medium),
                    ) {
                        item {
                            DuckCountTextField(
                                value = state.title,
                                labelName = stringResource(androidUiR.string.title),
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
                                    onValueChange = { name, symbol, hasSubUnits ->
                                        viewModel.updateCurrency(name, symbol, hasSubUnits)
                                    },
                                )
                            }
                        }

                        item {
                            DuckCountTextField(
                                value = state.description,
                                labelName = stringResource(androidUiR.string.description),
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
                            authenticatedUserUuid = viewModel.getAuthenticatedUserUuid(),
                            onModifyMemberList = {
                                viewModel.showMemberDialog()
                            },
                            removeMember = { member ->
                                viewModel.removeMember(member)
                            },
                        )
                    }

                    ElevatedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Padding.extraLarge)
                            .align(Alignment.BottomCenter),
                        enabled = viewModel.isOwnerInMembers()
                                && state.members.size >= 2
                                && state.title.length in (1..viewModel.maxTitleLength)
                                && state.description.length in (0..viewModel.maxDescriptionLength),
                        colors = ButtonDefaults.buttonColors()
                            .copy(containerColor = MaterialTheme.colorScheme.primary),
                        onClick = {
                            viewModel.groupUuid?.let {
                                viewModel.updateGroup()
                            } ?: run { viewModel.createGroup() }

                            onBack()
                        },
                    ) {
                        Text(
                            modifier = Modifier.padding(Padding.small),
                            text = stringResource(androidUiR.string.confirm),
                        )
                    }
                }
            }
        }
    }
}
