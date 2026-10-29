import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedLoadingButton
import app.lidory.duckcount.android.ui.constant.Padding
import component.SafetyNumberCard
import component.VerifiedLabel
import model.ContactVerification
import model.messageRes
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactVerificationViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * Where the user compares the safety number they share with a contact, read out in person or on
 * a call they trust, to tell that nobody stands between the two of them, and marks it verified.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactVerificationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactVerificationViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.verify_safety_number))
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
        bottomBar = {
            // Only a number on screen can be marked.
            (state.verification as? ContactVerification.Available)?.let { verification ->
                DuckCountElevatedLoadingButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Padding.medium)
                        .padding(bottom = Padding.extraLarge),
                    enabled = !state.isUpdating,
                    colors = ButtonDefaults.buttonColors()
                        .copy(containerColor = MaterialTheme.colorScheme.primary),
                    onClick = viewModel::toggleVerified,
                    isLoading = state.isUpdating,
                ) {
                    Text(
                        modifier = Modifier.padding(Padding.small),
                        text = stringResource(
                            if (verification.isVerified) R.string.clear_verification else R.string.mark_as_verified,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        when (val verification = state.verification) {
            null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ContactVerification.NotContact -> {
                EmptyContentContainer(
                    modifier = Modifier.padding(innerPadding),
                    icon = androidUiR.drawable.ic_contacts_group,
                    text = R.string.contact_verification_not_contact,
                )
            }

            is ContactVerification.Unavailable -> {
                EmptyContentContainer(
                    modifier = Modifier.padding(innerPadding),
                    icon = androidUiR.drawable.ic_error,
                    text = verification.error.messageRes(),
                )
            }

            ContactVerification.IdentityChanged -> {
                IdentityChangedWarning(
                    modifier = Modifier.padding(innerPadding),
                    fullName = state.fullName,
                )
            }

            is ContactVerification.Available -> {
                SafetyNumberComparison(
                    modifier = Modifier.padding(innerPadding),
                    fullName = state.fullName,
                    verification = verification,
                )
            }
        }
    }
}

/**
 * The safety number to compare, then what comparing it is for, which reads as a caption of the
 * numbers above it, and whether the user marked it verified.
 */
@Composable
private fun SafetyNumberComparison(
    fullName: String?,
    verification: ContactVerification.Available,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Padding.medium, vertical = Padding.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Padding.large),
    ) {
        ContactName(fullName = fullName)

        SafetyNumberCard(safetyNumber = verification.safetyNumber)

        Text(
            textAlign = TextAlign.Center,
            text = stringResource(R.string.safety_number_explanation),
        )

        if (verification.isVerified) {
            VerifiedLabel()
        }
    }
}

/**
 * Stands in for the safety number of a contact whose identity is in doubt: whatever number this
 * device computed would vouch for the very identity in question.
 */
@Composable
private fun IdentityChangedWarning(
    fullName: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Padding.medium, vertical = Padding.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Padding.large),
    ) {
        ContactName(fullName = fullName)

        Icon(
            modifier = Modifier.size(32.dp),
            painter = painterResource(androidUiR.drawable.ic_error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )

        Text(
            textAlign = TextAlign.Center,
            text = stringResource(R.string.safety_number_changed),
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            textAlign = TextAlign.Center,
            text = stringResource(R.string.safety_number_changed_explanation),
        )
    }
}

@Composable
private fun ContactName(fullName: String?) {
    Text(
        textAlign = TextAlign.Center,
        text = fullName ?: stringResource(R.string.unresolved_user),
        style = MaterialTheme.typography.headlineSmall,
    )
}
