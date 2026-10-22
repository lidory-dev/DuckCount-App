package app.lidory.duckcount.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.button.PasswordVisibilityButton
import app.lidory.duckcount.android.ui.composable.text.DuckCountErrorText
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.model.messageRes
import app.lidory.duckcount.settings.viewmodel.AccountDeletionViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDeletionScreen(
    onBack: () -> Unit,
) {
    val viewModel: AccountDeletionViewModel = koinViewModel()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    var isPasswordVisible by remember { mutableStateOf(false) }

    // Do not allow navigating back while deleting the account.
    BackHandler(enabled = isDeleting) {}

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
                        enabled = !isDeleting,
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
        BoxWithConstraints(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                    start = Padding.large,
                    end = Padding.large,
                )
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            // Scrolls when the form does not fit, above the keyboard, in landscape or with a large
            // font for instance, and is at least as tall as the room it has so that the button sits
            // at the bottom while there is room for it, and scrolls with the form rather than
            // hiding the field when there is not.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
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
                            contentType = ContentType.Password
                        }
                        .fillMaxWidth(),
                    value = password,
                    onValueChange = { viewModel.onPasswordChanged(it) },
                    focusManager = focusManager,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    labelName = stringResource(R.string.password),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next,
                    ),
                    enabled = !isDeleting,
                    isInError = password.isBlank(),
                    error = stringResource(androidUiR.string.required),
                    trailingIcon = {
                        PasswordVisibilityButton(
                            onClick = { isPasswordVisible = !isPasswordVisible },
                            isPasswordVisible = isPasswordVisible,
                        )
                    },
                )

                error?.let { error ->
                    DuckCountErrorText(
                        modifier = Modifier.padding(top = Padding.medium),
                        text = stringResource(error.messageRes()),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Spacer(modifier = Modifier.height(24.dp))

                DuckCountLoadingButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.onConfirmClicked()
                    },
                    enabled = password.isNotBlank() && !isDeleting,
                    isLoading = isDeleting,
                    colors = ButtonDefaults.buttonColors().copy(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(androidUiR.string.confirm))
                }
            }
        }
    }
}
