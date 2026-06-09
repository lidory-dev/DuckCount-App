package app.lidory.duckcount.groupList

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.groupMembersDetail.GroupMemberBottomSheet
import app.lidory.duckcount.groupList.component.groupMembersDetail.groupMemberDetail
import app.lidory.duckcount.groupList.viewmodel.GroupSettingsViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    onBack: () -> Unit,
    navigateToEditGroup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bottomSheetState = rememberModalBottomSheetState()
    var showBottomSheet by remember { mutableStateOf(false) }
    var bottomSheetUserUuid by remember { mutableStateOf("") }

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
                    IconButton(
                        onClick = navigateToEditGroup,
                    ) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_edit),
                            contentDescription = stringResource(androidUiR.string.edit),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .padding(innerPadding)
                .padding(horizontal = Padding.medium),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Padding.extraLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.groupWithMembers?.title ?: "",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = state.groupWithMembers?.description ?: "",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            groupMemberDetail(
                members = state.membersWithFullName,
                openBottomSheet = { userUuid ->
                    showBottomSheet = true
                    bottomSheetUserUuid = userUuid
                },
            )
        }

        if (showBottomSheet) {
            GroupMemberBottomSheet(
                userUuid = bottomSheetUserUuid,
                removeUserFromGroup = { userUuid ->
                    showBottomSheet = false
                    viewModel.removeGroupMember(userUuid)
                },
                bottomSheetState = bottomSheetState,
                onDismissRequest = {
                    showBottomSheet = false
                    bottomSheetUserUuid = ""
                },
            )
        }
    }
}
