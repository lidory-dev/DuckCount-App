import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.LocalAutofillHighlightBrush
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.button.PasswordVisibilityButton
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.authentication.R
import org.koin.androidx.compose.koinViewModel
import viewmodel.RegisterViewModel
import app.lidory.duckcount.android.ui.R as androidUiR


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    navigateBack: () -> Unit,
    navigateToLeakPasswordScreen: (Int) -> Unit,
    navigateToSetUpProfileScreen: (String, String, Boolean) -> Unit,
) {
    val viewModel = koinViewModel<RegisterViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val focusManager: FocusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val autofillManager = LocalAutofillManager.current

    var isPasswordVisible by remember { mutableStateOf(false) }

    // We need to clear the passwords if we land back on this screen
    // after being on the PasswordLeakScreen.
    LaunchedEffect(Unit) {
        viewModel.clearPasswords()
    }

    // Do not allow navigating back while actually registering.
    BackHandler(enabled = state.isLoading) {}

    CompositionLocalProvider(
        LocalAutofillHighlightBrush provides SolidColor(Color.Transparent),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    focusManager.clearFocus()
                },
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(
                            enabled = !state.isLoading,
                            onClick = navigateBack,
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Padding.extraLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Spacer(modifier = Modifier.weight(.7f))

                    Text(
                        text = stringResource(R.string.welcome),
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        lineHeight = 40.sp,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    DuckCountTextField(
                        modifier = Modifier
                            .semantics {
                                contentType = ContentType.Username
                            }
                            .fillMaxWidth(),
                        singleLine = true,
                        value = state.username,
                        onValueChange = { viewModel.updateUsername(it) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        labelName = stringResource(R.string.username),
                        focusManager = focusManager,
                        isInError = state.usernameError != null,
                        error = state.usernameError?.let { stringResource(it.messageRes()) },
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    DuckCountTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentType = ContentType.NewPassword
                            },
                        singleLine = true,
                        value = state.password,
                        onValueChange = { viewModel.updatePassword(it) },
                        labelName = stringResource(R.string.password),
                        focusManager = focusManager,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        isInError = state.password.isNotEmpty() && viewModel.isPasswordTooShort(),
                        error = stringResource(
                            R.string.error_password_short,
                            viewModel.passwordStrength,
                        ),
                        trailingIcon = {
                            PasswordVisibilityButton(
                                onClick = { isPasswordVisible = !isPasswordVisible },
                                isPasswordVisible = isPasswordVisible,
                            )
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DuckCountTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentType = ContentType.NewPassword
                            },
                        singleLine = true,
                        value = state.passwordConfirmation,
                        onValueChange = { viewModel.updatePasswordConfirmation(it) },
                        labelName = stringResource(R.string.password_confirmation),
                        focusManager = focusManager,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (!viewModel.isPasswordConfirmationValid()
                                    || state.passwordConfirmation.isBlank()
                                ) return@KeyboardActions

                                focusManager.clearFocus()

                                viewModel.processRegistration(
                                    username = state.username,
                                    password = state.password,
                                    navigateToPasswordLeakScreen = navigateToLeakPasswordScreen,
                                    navigateToUserInformationScreen = navigateToSetUpProfileScreen,
                                )
                            },
                        ),
                        isInError = !viewModel.isPasswordConfirmationValid()
                                && state.passwordConfirmation.isNotBlank(),
                        error = stringResource(R.string.error_password_confirmation),
                        trailingIcon = {
                            PasswordVisibilityButton(
                                onClick = { isPasswordVisible = !isPasswordVisible },
                                isPasswordVisible = isPasswordVisible,
                            )
                        },
                    )

                    state.error?.let { error ->
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = stringResource(error.messageRes()),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    DuckCountLoadingButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            autofillManager?.commit()
                            viewModel.processRegistration(
                                username = state.username,
                                password = state.password,
                                navigateToPasswordLeakScreen = navigateToLeakPasswordScreen,
                                navigateToUserInformationScreen = navigateToSetUpProfileScreen,
                            )
                        },
                        enabled = state.username.isNotBlank() && state.usernameError == null && !viewModel.isPasswordTooShort() && viewModel.isPasswordConfirmationValid() && !state.isLoading,
                        isLoading = state.isLoading,
                    ) {
                        Text(
                            text = stringResource(R.string.register),
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1.5f))
                }
            }
        }
    }
}
