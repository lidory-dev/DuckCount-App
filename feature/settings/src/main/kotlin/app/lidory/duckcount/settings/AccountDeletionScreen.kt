package app.lidory.duckcount.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.viewmodel.AccountDeletionViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDeletionScreen(
    onBack: () -> Unit,
    onAccountDeletion: () -> Unit, // TODO.
) {
    val viewModel: AccountDeletionViewModel = koinViewModel()
    val password = viewModel.password.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.delete_account),
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
        Column(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                    start = Padding.large,
                    end = Padding.large,
                ),
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors().copy(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Icon(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = Padding.large)
                        .size(32.dp),
                    painter = painterResource(R.drawable.ic_dangerous),
                    contentDescription = null,
                )

                Text(
                    modifier = Modifier.padding(
                        top = Padding.small,
                        bottom = Padding.medium,
                        start = Padding.medium,
                        end = Padding.medium,
                    ),
                    textAlign = TextAlign.Center,
                    text = stringResource(R.string.account_deletion_disclaimer),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            DuckCountTextField(
                modifier = Modifier
                    .semantics {
                        contentType = ContentType.PersonFullName
                    }
                    .fillMaxWidth(),
                value = password.value,
                onValueChange = { viewModel.onPasswordChanged(it) },
                focusManager = focusManager,
                singleLine = true,
                labelName = stringResource(R.string.password),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                ),
                isInError = password.value.isBlank(),
                error = stringResource(androidUiR.string.required),
            )

            Spacer(Modifier.weight(1f))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.onConfirmClicked() },
                enabled = password.value.isNotBlank(),
                colors = ButtonDefaults.buttonColors().copy(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(androidUiR.string.confirm))
            }
        }
    }
}
