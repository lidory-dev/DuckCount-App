import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import component.ContactDeletionDialog
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactDetailViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Subscribe once (keyed on Unit, so it survives recomposition) to the ViewModel's
    // one-shot navigateBack events and forward them to navigation. The ViewModel emits
    // this after deleting a contact, since there is no detail left to show.
    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
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
                    ) {
                        Icon(
                            painter = painterResource((androidUiR.drawable.ic_delete)),
                            contentDescription = stringResource(androidUiR.string.remove),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
        }
    }
}
