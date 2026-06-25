package app.lidory.duckcount.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.FontSize
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.viewmodel.ChangeFullNameViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeFullNameScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val viewModel: ChangeFullNameViewModel = koinViewModel()

    val state by viewModel.state.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(androidUiR.string.full_name)) },
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
                .fillMaxHeight()
                .padding(innerPadding)
                .padding(horizontal = Padding.extraLarge),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Column {
                Text(
                    text = stringResource(R.string.change_fullName),
                    fontWeight = FontWeight.Bold,
                    fontSize = FontSize.large,
                    lineHeight = FontSize.title,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.change_fullName_description),
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
                        imeAction = ImeAction.Done,
                    ),
                    isInError = state.fullName.isBlank(),
                    error = stringResource(androidUiR.string.required),
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.confirmChangeFullName()
                        onBack()
                    },
                    enabled = state.fullName.isNotBlank(),
                ) {
                    Text(text = stringResource(androidUiR.string.confirm))
                }
            }

            Spacer(modifier = Modifier.weight(1.5f))
        }
    }
}
