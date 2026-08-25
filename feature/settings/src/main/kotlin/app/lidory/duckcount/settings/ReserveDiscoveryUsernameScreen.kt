package app.lidory.duckcount.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
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

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
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
                title = { Text(text = stringResource(R.string.username)) },
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
                .padding(horizontal = Padding.large),
            verticalArrangement = Arrangement.Center,
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
                labelName = stringResource(R.string.username),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                isInError = state.inputError != null,
                error = state.inputError?.let { stringResource(it.messageRes()) },
            )

            state.error?.let { error ->
                Text(
                    modifier = Modifier.padding(
                        top = Padding.medium,
                        start = Padding.medium,
                        end = Padding.medium,
                    ),
                    text = stringResource(error.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.weight(1.5f))

            DuckCountButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    focusManager.clearFocus()
                    onReserveClicked()
                },
                enabled = state.canReserve,
            ) {
                if (state.isReserving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(text = stringResource(R.string.reserve))
                }
            }
        }
    }
}
