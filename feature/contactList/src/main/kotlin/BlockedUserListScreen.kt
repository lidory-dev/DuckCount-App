import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.android.ui.text.sortedByName
import model.BlockedUser
import org.koin.androidx.compose.koinViewModel
import viewmodel.BlockedUserListState
import viewmodel.BlockedUserListViewModel
import app.lidory.duckcount.android.ui.R as SharedR

/**
 * Everybody the user blocked, each with a way to unblock them, which leaves them no contact: a new
 * request, from either of the two, is what makes them one again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUserListScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<BlockedUserListViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The user the user asked to unblock, until they confirm or dismiss it: an unblock cannot be
    // taken back from here, somebody who is no contact being blocked only from a request of theirs.
    var unblocked by remember { mutableStateOf<BlockedUser?>(null) }

    unblocked?.let { user ->
        UnblockUserDialog(
            onDismiss = { unblocked = null },
            onConfirm = {
                viewModel.unblock(userUuid = user.userUuid)
                unblocked = null
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.blocked_users),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) {
                        Icon(
                            painter = painterResource(SharedR.drawable.ic_arrow_back),
                            contentDescription = stringResource(SharedR.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val content = state) {
                BlockedUserListState.Loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is BlockedUserListState.Content -> if (content.users.isEmpty()) {
                    EmptyContentContainer(
                        modifier = Modifier.weight(1f),
                        icon = R.drawable.ic_block,
                        text = R.string.no_blocked_users,
                    )
                } else {
                    val users = remember(content.users) {
                        content.users.sortedByName(selfUuid = null, uuidOf = { it.userUuid }, nameOf = { it.fullName })
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = Padding.medium),
                        verticalArrangement = Arrangement.spacedBy(Padding.medium),
                    ) {
                        item {
                            Text(
                                modifier = Modifier.padding(horizontal = Padding.medium),
                                text = stringResource(R.string.blocked_users_explanation),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        items(
                            items = users,
                            key = { user -> user.userUuid },
                        ) { user ->
                            BlockedUserCard(
                                modifier = Modifier.animateItem(),
                                user = user,
                                onUnblock = { unblocked = user },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedUserCard(
    user: BlockedUser,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = user.fullName ?: stringResource(R.string.unresolved_user)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight)
                .padding(end = Padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfilePictureCard(
                modifier = Modifier.fillMaxHeight(),
                uuid = user.userUuid,
                fullName = name,
            )

            Text(
                modifier = Modifier.weight(1f),
                text = name,
            )

            TextButton(onClick = onUnblock) {
                Text(text = stringResource(R.string.unblock_user))
            }
        }
    }
}

@Composable
private fun UnblockUserDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.unblock_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(SharedR.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.unblock_user_title)) },
        text = { Text(text = stringResource(R.string.unblock_user_confirmation)) },
    )
}
