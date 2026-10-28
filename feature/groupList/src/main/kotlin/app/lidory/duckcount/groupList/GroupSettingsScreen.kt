package app.lidory.duckcount.groupList

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.button.DuckCountOutlinedButton
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.GroupLeaveDialog
import app.lidory.duckcount.groupList.component.GroupMemberFullScreenDialog
import app.lidory.duckcount.groupList.component.LeftGroupNotice
import app.lidory.duckcount.groupList.component.groupMembersDetail.GroupMemberBottomSheet
import app.lidory.duckcount.groupList.component.groupMembersDetail.GroupMemberRemovalDialog
import app.lidory.duckcount.groupList.component.groupMembersDetail.groupMemberDetail
import app.lidory.duckcount.groupList.model.MembershipChangeError
import app.lidory.duckcount.groupList.model.MembershipChangeResult
import app.lidory.duckcount.groupList.model.message
import app.lidory.duckcount.groupList.viewmodel.GroupSettingsViewModel
import app.lidory.duckcount.groupList.viewmodel.MemberActions
import app.lidory.duckcount.groupList.viewmodel.isLeavable
import app.lidory.duckcount.groupList.viewmodel.memberActions
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    onBack: () -> Unit,
    navigateToEditGroup: () -> Unit,
    navigateToContactDetail: (userUuid: String) -> Unit,
    navigateToContactRequests: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupSettingsViewModel = koinViewModel(),
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val userListState by viewModel.usersState.collectAsStateWithLifecycle()
    val selectedUuids by viewModel.selectedUuids.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isChanging by viewModel.isChanging.collectAsStateWithLifecycle()
    val memberSheet by viewModel.memberSheet.collectAsStateWithLifecycle()

    val group = state.groupWithMembers
    val actions = group?.memberActions() ?: MemberActions.NONE

    // The screen's snackbars, and the picker's, which covers the screen while open.
    val snackbarHostState = remember { SnackbarHostState() }
    val pickerSnackbarHostState = remember { SnackbarHostState() }
    var pendingNotice by remember { mutableStateOf<MembershipChangeResult?>(null) }
    var pendingAdditionError by remember { mutableStateOf<MembershipChangeError?>(null) }

    // Tell what came of a change once answered, and who it was about.
    LaunchedEffect(Unit) {
        viewModel.notices.collect { pendingNotice = it }
    }

    LaunchedEffect(Unit) {
        viewModel.additionErrors.collect { pendingAdditionError = it }
    }

    pendingNotice?.let { notice ->
        val message = notice.message()

        LaunchedEffect(notice) {
            message?.let { snackbarHostState.showSnackbar(it) }
            pendingNotice = null
        }
    }

    pendingAdditionError?.let { error ->
        val message = error.message()

        LaunchedEffect(error) {
            pickerSnackbarHostState.showSnackbar(message)
            pendingAdditionError = null
        }
    }

    // What the picker told goes with it once it closes, rather than showing again the next time it
    // opens: its snackbars are gone from the screen with it, and nothing would time them out.
    val isMemberDialogShown = viewModel.isMemberDialogShown

    LaunchedEffect(isMemberDialogShown) {
        if (!isMemberDialogShown) pickerSnackbarHostState.currentSnackbarData?.dismiss()
    }

    GroupMemberFullScreenDialog(
        isDialogShown = isMemberDialogShown,
        onSelectUsers = { viewModel.addMembers() },
        onDismissRequest = { viewModel.hideMemberDialog() },
        userList = userListState.users,
        searchQuery = searchQuery,
        // Cleared with the selection once the picker closes, and only then: not while the members
        // picked are being added, see GroupSettingsViewModel.hideMemberDialog.
        clearSearchQuery = {},
        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
        toggleSelectedUser = { viewModel.toggleSelectedUser(it.uuid) },
        selectedUuids = selectedUuids,
        maxMembers = viewModel.maxMembers,
        currentMemberCount = state.membersWithFullName.size,
        // Members are added one at least.
        isConfirmEnabled = selectedUuids.isNotEmpty(),
        isConfirming = isChanging,
        snackbarHostState = pickerSnackbarHostState,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
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
                actions = {
                    // Read-only once the user is no longer in the group, removed or left.
                    if (group?.isLeft == false) {
                        IconButton(
                            onClick = navigateToEditGroup,
                        ) {
                            Icon(
                                painter = painterResource(androidUiR.drawable.ic_edit),
                                contentDescription = stringResource(androidUiR.string.edit),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                modifier = modifier
                    .weight(1f)
                    .padding(horizontal = Padding.medium),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Padding.extraLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        with(sharedTransitionScope) {
                            Text(
                                modifier = Modifier.sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = "group_title_${group?.uuid}"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                ),
                                text = group?.title ?: "",
                                style = MaterialTheme.typography.headlineLarge,
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                modifier = Modifier.sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = "group_description_${group?.uuid}"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                ),
                                text = group?.description ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (group?.isLeft == true) {
                    item {
                        LeftGroupNotice(modifier = Modifier.padding(bottom = Padding.extraLarge))
                    }
                }

                groupMemberDetail(
                    members = state.membersWithFullName,
                    authenticatedUserUuid = viewModel.getAuthenticatedUserUuid(),
                    actions = actions,
                    onAddMembers = { viewModel.showMemberDialog() },
                    openBottomSheet = { member -> viewModel.openMember(member) },
                )

                // Last, as the sheet's removal is: what the user does about the group as a whole,
                // and the one way out of it, outlined in the error colour. Gone once they are out,
                // the group then read-only, with the notice above saying so.
                if (group?.isLeavable() == true) {
                    item {
                        DuckCountOutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Padding.extraLarge),
                            onClick = { viewModel.askToLeave() },
                            enabled = !isChanging,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = !isChanging),
                        ) {
                            Text(text = stringResource(R.string.leave_the_group))
                        }
                    }
                }
            }

            DuckCountSnackbarHost(hostState = snackbarHostState)
        }

        memberSheet?.let { sheet ->
            GroupMemberBottomSheet(
                sheet = sheet,
                onViewProfile = {
                    viewModel.closeMember()
                    navigateToContactDetail(sheet.member.member.userUuid)
                },
                onAnswerRequest = {
                    viewModel.closeMember()
                    navigateToContactRequests()
                },
                onSendRequest = { viewModel.sendContactRequest(sheet.member) },
                onRemove = {
                    viewModel.closeMember()
                    viewModel.askToRemove(sheet.member)
                },
                onDismissRequest = { viewModel.closeMember() },
            )
        }

        viewModel.memberToRemove?.let { member ->
            GroupMemberRemovalDialog(
                memberName = member.fullName ?: stringResource(R.string.unknown_member),
                groupTitle = group?.title ?: "",
                isRemoving = isChanging,
                onDismiss = { viewModel.cancelRemoval() },
                onConfirm = { viewModel.removeMember(member) },
            )
        }

        // Stays on the screen once left: the group, read again, shows read-only, see LeftGroupNotice.
        if (viewModel.isLeaveAsked) {
            GroupLeaveDialog(
                groupTitle = group?.title ?: "",
                isLeaving = isChanging,
                onDismiss = { viewModel.cancelLeave() },
                onConfirm = { viewModel.leave() },
            )
        }
    }
}
