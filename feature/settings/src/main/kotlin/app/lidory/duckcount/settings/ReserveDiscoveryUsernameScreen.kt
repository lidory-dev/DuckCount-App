package app.lidory.duckcount.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.text.DuckCountErrorText
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.model.messageRes
import app.lidory.duckcount.settings.viewmodel.DiscoveryUsernameScreenState
import app.lidory.duckcount.settings.viewmodel.ReserveDiscoveryUsernameViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun ReserveDiscoveryUsernameScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val viewModel = koinViewModel<ReserveDiscoveryUsernameViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isReserved by viewModel.isReserved.collectAsStateWithLifecycle()

    // Close once the username is reserved (see ReserveDiscoveryUsernameViewModel.isReserved),
    // and on resuming: onBack does nothing until the screen is resumed, so a reservation that
    // completes while the app is in the background closes the screen once the user is back.
    LifecycleResumeEffect(isReserved) {
        if (isReserved) onBack()

        onPauseOrDispose {}
    }

    when (val state = state) {
        DiscoveryUsernameScreenState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is DiscoveryUsernameScreenState.Content -> {
            ReserveDiscoveryUsernameContent(
                modifier = modifier,
                state = state,
                isReserved = isReserved,
                maxUsernameLength = viewModel.maxUsernameLength,
                onBack = onBack,
                onUsernameChanged = viewModel::onUsernameChanged,
                onReserveClicked = viewModel::onReserveClicked,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReserveDiscoveryUsernameContent(
    modifier: Modifier,
    state: DiscoveryUsernameScreenState.Content,
    isReserved: Boolean,
    maxUsernameLength: Int,
    onBack: () -> Unit,
    onUsernameChanged: (String) -> Unit,
    onReserveClicked: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    // Do not allow navigating back while reserving the username.
    BackHandler(enabled = state.isReserving) {}

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
                title = { Text(text = stringResource(R.string.discovery_username)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !state.isReserving,
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
            // Scrolls, the button staying pinned below, so that the field stays within reach when
            // the card takes up most of the height, in landscape or with a large font for instance.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = Padding.large)
                            .size(32.dp),
                        painter = painterResource(androidUiR.drawable.ic_username),
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
                        text = stringResource(
                            if (state.hasReservedUsername) {
                                R.string.change_username_disclaimer
                            } else {
                                R.string.reserve_username_disclaimer
                            },
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                DuckCountTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.username,
                    onValueChange = onUsernameChanged,
                    focusManager = focusManager,
                    singleLine = true,
                    labelName = stringResource(R.string.discovery_username),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    enabled = !state.isReserving,
                    isInError = state.inputError != null,
                    error = state.inputError?.let {
                        stringResource(it.messageRes(), state.username.length, maxUsernameLength)
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
            }

            DuckCountLoadingButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    focusManager.clearFocus()
                    onReserveClicked()
                },
                enabled = state.canReserve && !isReserved,
                isLoading = state.isReserving,
            ) {
                Text(text = stringResource(R.string.reserve))
            }
        }
    }
}
