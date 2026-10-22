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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.text.DuckCountErrorText
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import model.messageRes
import org.koin.androidx.compose.koinViewModel
import viewmodel.NewContactViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewContactScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }

    val viewModel = koinViewModel<NewContactViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    state.sentTo?.let { username ->
        val message = stringResource(R.string.contact_request_sent_to, username)

        LaunchedEffect(username) {
            snackbarHostState.showSnackbar(message)
            viewModel.onSentConfirmed(username = username)
        }
    }

    // Typing a username is all this screen is for. Only on entering it though, not once the
    // activity is recreated: the field would take focus with its cursor before what was typed.
    var focusedOnEntry by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (focusedOnEntry) return@LaunchedEffect

        focusedOnEntry = true
        focusRequester.requestFocus()
    }

    // Do not allow navigating back while sending the contact request.
    BackHandler(enabled = state.isSending) {}

    Scaffold(
        modifier = modifier
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
                        text = stringResource(R.string.add_new_contact),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !state.isSending,
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
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .padding(horizontal = Padding.large),
        ) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f),
            ) {
                // Scrolls when the form does not fit, above the keyboard, in landscape or with a
                // large font for instance, and is at least as tall as the room it has so that the
                // button sits at the bottom while there is room for it, and scrolls with the form
                // rather than hiding the field when there is not.
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight),
                ) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = Padding.large)
                                .size(32.dp),
                            painter = painterResource(R.drawable.ic_person_add),
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
                            text = stringResource(R.string.add_contact_disclaimer),
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    DuckCountTextField(
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            // Starting the next request dismisses the confirmation of the last
                            // one, which would otherwise leave the field hardly any room above the
                            // keyboard, in landscape for instance.
                            .onFocusChanged {
                                if (it.isFocused) snackbarHostState.currentSnackbarData?.dismiss()
                            }
                            .fillMaxWidth(),
                        value = state.username,
                        onValueChange = viewModel::updateUsername,
                        focusManager = focusManager,
                        singleLine = true,
                        labelName = stringResource(R.string.contact_username),
                        keyboardOptions = KeyboardOptions(
                            // Usernames are ASCII letters and digits.
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Send,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                // Nothing to send yet, so the keyboard stays up to finish typing.
                                if (!state.canSend) return@KeyboardActions

                                focusManager.clearFocus()
                                viewModel.sendContactRequest()
                            },
                        ),
                        enabled = !state.isSending,
                        isInError = state.inputError != null,
                        error = state.inputError?.let {
                            stringResource(
                                it.messageRes(),
                                state.trimmedUsername.length,
                                viewModel.maxUsernameLength,
                            )
                        },
                    )

                    state.error?.let { error ->
                        DuckCountErrorText(
                            modifier = Modifier.padding(
                                top = Padding.medium,
                                start = Padding.medium,
                                end = Padding.medium,
                            ),
                            text = stringResource(error.messageRes()),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Spacer(modifier = Modifier.height(24.dp))

                    DuckCountLoadingButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.sendContactRequest()
                        },
                        enabled = state.canSend,
                        isLoading = state.isSending,
                    ) {
                        Text(text = stringResource(R.string.send_contact_request))
                    }
                }
            }

            DuckCountSnackbarHost(hostState = snackbarHostState)
        }
    }
}
