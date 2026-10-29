import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.LocalAutofillHighlightBrush
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.button.PasswordVisibilityButton
import app.lidory.duckcount.android.ui.composable.text.DuckCountErrorText
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.FontSize
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.android.ui.text.LowercaseVisualTransformation
import app.lidory.duckcount.authentication.R
import org.koin.androidx.compose.koinViewModel
import viewmodel.LoginViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navigateBack: () -> Unit,
    navigateToDashboard: () -> Unit,
) {
    val viewModel = koinViewModel<LoginViewModel>()
    val state = viewModel.state

    val focusManager: FocusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    var isPasswordVisible by remember { mutableStateOf(false) }

    // Do not allow navigating back while actually logging in.
    BackHandler(enabled = state.isLoading) {}

    CompositionLocalProvider(
        LocalAutofillHighlightBrush provides SolidColor(Color.Transparent),
    ) {
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                // Scrolls when the form does not fit, in landscape or with a large font for
                // instance, and is at least as tall as the screen so that the spacers share what
                // the form leaves.
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = Padding.extraLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Spacer(modifier = Modifier.weight(.7f))

                    Text(
                        text = stringResource(R.string.welcome_back),
                        modifier = Modifier,
                        fontWeight = FontWeight.Bold,
                        fontSize = FontSize.extraLarge,
                        lineHeight = FontSize.extraLarge,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    DuckCountTextField(
                        modifier = Modifier
                            .semantics {
                                contentType = ContentType.Username
                            }
                            .fillMaxWidth(),
                        value = state.username,
                        onValueChange = {
                            viewModel.updateUsername(it)
                        },
                        labelName = stringResource(R.string.username),
                        focusManager = focusManager,
                        visualTransformation = LowercaseVisualTransformation,
                        isInError = state.usernameError != null,
                        error = state.usernameError?.let {
                            stringResource(it.messageRes(), state.trimmedUsername.length, viewModel.maxUsernameLength)
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DuckCountTextField(
                        modifier = Modifier
                            .semantics {
                                contentType = ContentType.Password
                            }
                            .fillMaxWidth(),
                        value = state.password,
                        onValueChange = {
                            viewModel.updatePassword(it)
                        },
                        labelName = stringResource(R.string.password),
                        focusManager = focusManager,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            PasswordVisibilityButton(
                                onClick = { isPasswordVisible = !isPasswordVisible },
                                isPasswordVisible = isPasswordVisible,
                            )
                        },
                    )

                    state.error?.let { error ->
                        Spacer(modifier = Modifier.height(12.dp))

                        DuckCountErrorText(text = stringResource(error.messageRes()))
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    DuckCountLoadingButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.processLogin(
                                username = state.trimmedUsername,
                                password = state.password,
                                navigateToExpenseListScreen = navigateToDashboard,
                            )
                        },
                        enabled = state.canLogIn,
                        isLoading = state.isLoading,
                    ) {
                        Text(
                            text = stringResource(R.string.login),
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1.5f))
                }
            }
        }
    }
}
