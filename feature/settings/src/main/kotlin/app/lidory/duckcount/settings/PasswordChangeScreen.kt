package app.lidory.duckcount.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.composable.button.PasswordVisibilityButton
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.viewmodel.PasswordChangeViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordChangeScreen(
    onBack: () -> Unit,
) {
    val viewModel: PasswordChangeViewModel = koinViewModel()
    val state = viewModel.state.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    var isCurrentPasswordVisible by remember { mutableStateOf(false) }
    var isNewPasswordVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.change_password),
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
            DuckCountTextField(
                modifier = Modifier
                    .semantics {
                        contentType = ContentType.Password
                    }
                    .fillMaxWidth(),
                value = state.value.currentPassword,
                onValueChange = { viewModel.onCurrentPasswordChanged(it) },
                focusManager = focusManager,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                visualTransformation = if (isCurrentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                isInError = state.value.currentPassword.isBlank(),
                error = stringResource(androidUiR.string.required),
                singleLine = true,
                labelName = stringResource(R.string.password),
                trailingIcon = {
                    PasswordVisibilityButton(
                        onClick = { isCurrentPasswordVisible = !isCurrentPasswordVisible },
                        isPasswordVisible = isCurrentPasswordVisible,
                    )
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            DuckCountTextField(
                modifier = Modifier
                    .semantics {
                        contentType = ContentType.Password
                    }
                    .fillMaxWidth(),
                value = state.value.newPassword,
                onValueChange = { viewModel.onNewPasswordChanged(it) },
                focusManager = focusManager,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                visualTransformation = if (isNewPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                labelName = stringResource(R.string.new_password),
                isInError = viewModel.isNewPasswordTooShort(),
                error = stringResource(
                    R.string.error_password_too_short,
                    viewModel.passwordStrength,
                ),
                trailingIcon = {
                    PasswordVisibilityButton(
                        onClick = { isNewPasswordVisible = !isNewPasswordVisible },
                        isPasswordVisible = isNewPasswordVisible,
                    )
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            DuckCountTextField(
                modifier = Modifier
                    .semantics {
                        contentType = ContentType.Password
                    }
                    .fillMaxWidth(),
                value = state.value.newPasswordConfirmation,
                onValueChange = { viewModel.onNewPasswordConfirmationChanged(it) },
                focusManager = focusManager,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                visualTransformation = if (isNewPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                labelName = stringResource(R.string.new_password_confirmation),
                isInError = !viewModel.isNewPasswordConfirmationValid(),
                error = stringResource(R.string.error_new_password_confirmation),
                trailingIcon = {
                    PasswordVisibilityButton(
                        onClick = { isNewPasswordVisible = !isNewPasswordVisible },
                        isPasswordVisible = isNewPasswordVisible,
                    )
                },
            )

            Spacer(Modifier.weight(1f))

            DuckCountButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.onConfirmClicked() },
                enabled = state.value.currentPassword.isNotBlank()
                        && !viewModel.isNewPasswordTooShort()
                        && viewModel.isNewPasswordConfirmationValid(),
            ) {
                Text(stringResource(androidUiR.string.confirm))
            }
        }
    }
}
