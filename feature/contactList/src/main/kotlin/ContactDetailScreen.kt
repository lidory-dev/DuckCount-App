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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactDetailViewModel
import app.lidory.duckcount.android.ui.R as SharedR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                .padding(innerPadding)
                .padding(horizontal = Padding.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.user?.let { user ->
                ProfilePictureCard(
                    modifier = Modifier.fillMaxWidth(.3f),
                    profilePicture = user.profilePicture,
                    uuid = user.uuid,
                    fullName = user.fullName,
                )

                Text(text = user.fullName)
            } ?: Text(text = stringResource(R.string.unresolved_user))
        }
    }
}
