import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import component.ContactDeletionDialog
import component.ContactVerificationCard
import model.ContactVerification
import model.RemoveContactError
import model.messageRes
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactDetailViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    navigateToContactVerification: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val isRemoved by viewModel.isRemoved.collectAsStateWithLifecycle()

    // Close once the contact is removed (see ContactDetailViewModel.isRemoved), and on resuming:
    // onBack does nothing until the screen is resumed, so a removal that completes while the app
    // is in the background closes the screen once the user is back.
    LifecycleResumeEffect(isRemoved) {
        if (isRemoved) onBack()

        onPauseOrDispose {}
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingError by remember { mutableStateOf<RemoveContactError?>(null) }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { pendingError = it }
    }

    pendingError?.let { error ->
        val message = stringResource(error.messageRes())

        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingError = null
        }
    }

    val isConfirmDeletionDialogVisible = remember { mutableStateOf(false) }

    if (isConfirmDeletionDialogVisible.value) {
        ContactDeletionDialog(
            onDismiss = { isConfirmDeletionDialogVisible.value = false },
            onConfirm = {
                viewModel.removeContact()
                isConfirmDeletionDialogVisible.value = false
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
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
                        onClick = { isConfirmDeletionDialogVisible.value = true },
                        enabled = !state.isRemoving && !isRemoved,
                    ) {
                        if (state.isRemoving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(
                                painter = painterResource((androidUiR.drawable.ic_delete)),
                                contentDescription = stringResource(androidUiR.string.remove),
                            )
                        }
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
            // Scrolls so that the entries below the picture stay within reach when the picture, as
            // wide as a third of the screen, takes up most of its height, in landscape for instance.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Padding.medium),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.user?.let { user ->
                    with(sharedTransitionScope) {
                        ProfilePictureCard(
                            modifier = Modifier
                                .fillMaxWidth(.3f)
                                .sharedElement(
                                    sharedContentState = rememberSharedContentState(key = "profile_picture_${user.uuid}"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                ),
                            profilePicture = user.profilePicture,
                            uuid = user.uuid,
                            fullName = user.fullName,
                        )

                        Text(
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "full_name_${user.uuid}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                            ),
                            text = user.fullName,
                        )
                    }
                } ?: Text(text = stringResource(R.string.unresolved_user))

                // Only a contact has an identity to check, and the entry waits until that is known.
                val shownVerification = verification

                if (shownVerification != null && shownVerification != ContactVerification.NotContact) {
                    Spacer(modifier = Modifier.height(Padding.extraLarge))

                    ContactVerificationCard(
                        isVerified = (shownVerification as? ContactVerification.Available)?.isVerified == true,
                        onClick = dropUnlessResumed { navigateToContactVerification() },
                    )
                }
            }

            DuckCountSnackbarHost(hostState = snackbarHostState)
        }
    }
}
