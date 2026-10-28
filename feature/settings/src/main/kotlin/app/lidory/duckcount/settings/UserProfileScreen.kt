package app.lidory.duckcount.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.text.DuckCountErrorText
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.model.messageRes
import app.lidory.duckcount.settings.viewmodel.UserProfileScreenState
import app.lidory.duckcount.settings.viewmodel.UserProfileViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val viewModel = koinViewModel<UserProfileViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val isSaving = (state as? UserProfileScreenState.Content)?.isSaving == true

    // Do not allow navigating back while saving the profile.
    BackHandler(enabled = isSaving) {}

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
                title = { Text(text = stringResource(R.string.profile)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !isSaving,
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
        when (val state = state) {
            is UserProfileScreenState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is UserProfileScreenState.NotFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = stringResource(androidUiR.string.an_error_occurred))
                }
            }

            is UserProfileScreenState.Content -> {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .imePadding()
                        .padding(horizontal = Padding.large),
                ) {
                    // Scrolls when the form does not fit, above the keyboard, in landscape or with
                    // a large font for instance, and is at least as tall as the room it has so that
                    // the button sits at the bottom while there is room for it, and scrolls with
                    // the form rather than hiding the field when there is not.
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = Padding.large)
                                    .size(32.dp),
                                painter = painterResource(androidUiR.drawable.ic_eye_with_lock),
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
                                text = stringResource(R.string.profile_disclaimer),
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        DuckCountTextField(
                            modifier = Modifier
                                .semantics {
                                    contentType = ContentType.PersonFullName
                                }
                                .fillMaxWidth(),
                            value = state.draftFullName,
                            onValueChange = { viewModel.onFullNameChanged(it) },
                            focusManager = focusManager,
                            singleLine = true,
                            labelName = stringResource(androidUiR.string.full_name),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next,
                            ),
                            enabled = !isSaving,
                            isInError = state.draftFullName.isBlank(),
                            error = stringResource(androidUiR.string.required),
                            counter = stringResource(
                                androidUiR.string.character_count,
                                state.draftFullName.length,
                                viewModel.maxFullNameLength,
                            ),
                        )

                        state.error?.let { error ->
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
                            enabled = state.canSave,
                            isLoading = state.isSaving,
                        ) {
                            Text(text = stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}
