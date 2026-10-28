package app.lidory.duckcount.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.button.PasswordVisibilityButton
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.model.PasswordChangeError
import app.lidory.duckcount.settings.model.messageRes
import app.lidory.duckcount.settings.viewmodel.PasswordChangeViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordChangeScreen(
    onBack: () -> Unit,
    onPasswordChanged: () -> Unit,
) {
    val viewModel: PasswordChangeViewModel = koinViewModel()
    val state = viewModel.state
    val isChanged by viewModel.isChanged.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val snackbarHostState = remember { SnackbarHostState() }

    val currentPasswordInView = remember { BringIntoViewRequester() }
    val newPasswordInView = remember { BringIntoViewRequester() }

    var isCurrentPasswordVisible by remember { mutableStateOf(false) }
    var isNewPasswordVisible by remember { mutableStateOf(false) }

    // Close once the password is changed (see PasswordChangeViewModel.isChanged), and on
    // resuming: onPasswordChanged does nothing until the screen is resumed, so a change that
    // completes while the app is in the background closes the screen once the user is back.
    LifecycleResumeEffect(isChanged) {
        if (isChanged) onPasswordChanged()

        onPauseOrDispose {}
    }

    val error = state.error

    // A failure on a field is brought into view as it appears, as DuckCountErrorText brings one:
    // the field is not focused then, which is when it would bring itself into view, and in
    // landscape or with a large font it can be out of view.
    LaunchedEffect(error) {
        when (error) {
            PasswordChangeError.WRONG_PASSWORD -> currentPasswordInView.bringIntoView()
            PasswordChangeError.LEAKED -> newPasswordInView.bringIntoView()
            else -> Unit
        }
    }

    // Every other failure has no field to be shown on, and shows below the form, never over its
    // button, see DuckCountSnackbarHost.
    error?.takeUnless { it.isFieldError }?.let { snackbarError ->
        val message = stringResource(snackbarError.messageRes())

        LaunchedEffect(snackbarError) {
            snackbarHostState.showSnackbar(
                message = message,
                // Long enough to be read through: it says what to do should the session end.
                duration = if (snackbarError == PasswordChangeError.UNCONFIRMED) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                },
            )
            viewModel.onErrorShown(error = snackbarError)
        }
    }

    // Do not allow navigating back while changing the password.
    BackHandler(enabled = state.isChanging) {}

    Scaffold(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                focusManager.clearFocus()
            }
            .fillMaxSize(),
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
                        enabled = !state.isChanging,
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
                )
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f),
            ) {
                // Scrolls when the form does not fit, above the keyboard, in landscape or with a
                // large font for instance, and is at least as tall as the room it has so that the
                // button sits at the bottom while there is room for it, and scrolls with the form
                // rather than hiding the fields when there is not.
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight),
                ) {
                    DuckCountTextField(
                        modifier = Modifier
                            .bringIntoViewRequester(currentPasswordInView)
                            .semantics {
                                contentType = ContentType.Password
                            }
                            .fillMaxWidth(),
                        value = state.currentPassword,
                        onValueChange = viewModel::onCurrentPasswordChanged,
                        focusManager = focusManager,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        visualTransformation = if (isCurrentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        enabled = !state.isChanging,
                        isInError = error == PasswordChangeError.WRONG_PASSWORD,
                        error = stringResource(PasswordChangeError.WRONG_PASSWORD.messageRes()),
                        singleLine = true,
                        labelName = stringResource(R.string.current_password),
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
                            .bringIntoViewRequester(newPasswordInView)
                            .semantics {
                                contentType = ContentType.NewPassword
                            }
                            .fillMaxWidth(),
                        value = state.newPassword,
                        onValueChange = viewModel::onNewPasswordChanged,
                        focusManager = focusManager,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        visualTransformation = if (isNewPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        enabled = !state.isChanging,
                        singleLine = true,
                        labelName = stringResource(R.string.new_password),
                        isInError = error == PasswordChangeError.LEAKED || state.newPasswordInputError != null,
                        error = if (error == PasswordChangeError.LEAKED) {
                            stringResource(error.messageRes())
                        } else {
                            state.newPasswordInputError?.let { stringResource(it.messageRes(), state.minimumLength) }
                        },
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
                                contentType = ContentType.NewPassword
                            }
                            .fillMaxWidth(),
                        value = state.newPasswordConfirmation,
                        onValueChange = viewModel::onNewPasswordConfirmationChanged,
                        focusManager = focusManager,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                // Nothing to change to yet, so the keyboard stays up to finish typing.
                                if (!state.canConfirm) return@KeyboardActions

                                focusManager.clearFocus()
                                viewModel.onConfirmClicked()
                            },
                        ),
                        visualTransformation = if (isNewPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        enabled = !state.isChanging,
                        singleLine = true,
                        labelName = stringResource(R.string.new_password_confirmation),
                        isInError = state.isConfirmationMismatched,
                        error = stringResource(R.string.error_new_password_confirmation),
                        trailingIcon = {
                            PasswordVisibilityButton(
                                onClick = { isNewPasswordVisible = !isNewPasswordVisible },
                                isPasswordVisible = isNewPasswordVisible,
                            )
                        },
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Spacer(modifier = Modifier.height(24.dp))

                    DuckCountLoadingButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.onConfirmClicked()
                        },
                        enabled = state.canConfirm && !isChanged,
                        isLoading = state.isChanging,
                    ) {
                        Text(stringResource(androidUiR.string.confirm))
                    }
                }
            }

            DuckCountSnackbarHost(hostState = snackbarHostState)
        }
    }
}
