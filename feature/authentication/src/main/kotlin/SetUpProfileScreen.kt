import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountLoadingButton
import app.lidory.duckcount.android.ui.composable.text.DuckCountErrorText
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.FontSize
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.authentication.R
import org.koin.androidx.compose.koinViewModel
import viewmodel.UserInformationViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun SetUpProfileScreen(
    uuid: String,
    username: String,
    hasPasswordBeenChecked: Boolean,
    navigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<UserInformationViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) navigateToDashboard()
    }

    // Do not allow navigating back while saving the profile.
    BackHandler(enabled = state.isSaving) {}

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    Scaffold(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                focusManager.clearFocus()
            }
            .fillMaxSize(),
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .padding(innerPadding),
        ) {
            // Scrolls when the form does not fit, in landscape or with a large font for instance,
            // and is at least as tall as the screen so that the spacers share what the form leaves.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = Padding.extraLarge),
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Column {
                    Text(
                        text = stringResource(R.string.set_up_your_profile_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = FontSize.large,
                        lineHeight = FontSize.title,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.set_up_your_profile_description),
                        modifier = Modifier.padding(top = Padding.large),
                        fontWeight = FontWeight.Normal,
                        fontSize = FontSize.medium,
                        lineHeight = FontSize.medium * 1.2f,
                    )
                }

                Spacer(modifier = Modifier.weight(.3f))

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    DuckCountTextField(
                        modifier = Modifier
                            .semantics {
                                contentType = ContentType.PersonFullName
                            }
                            .fillMaxWidth(),
                        value = state.fullName,
                        onValueChange = { viewModel.updateFullName(it) },
                        focusManager = focusManager,
                        singleLine = true,
                        labelName = stringResource(androidUiR.string.full_name),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next,
                        ),
                        isInError = state.fullName.isBlank(),
                        error = stringResource(androidUiR.string.required),
                    )

                    state.error?.let { error ->
                        Spacer(modifier = Modifier.height(12.dp))

                        DuckCountErrorText(text = stringResource(error.messageRes()))
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    DuckCountLoadingButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.insertUser(
                                uuid = uuid,
                                username = username,
                                fullName = state.fullName,
                                hasPasswordBeenChecked = hasPasswordBeenChecked,
                            )
                        },
                        enabled = state.canSave,
                        isLoading = state.isSaving,
                    ) {
                        Text(text = stringResource(R.string.done))
                    }
                }

                Spacer(modifier = Modifier.weight(1.5f))
            }
        }
    }
}
