package app.lidory.duckcount.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.component.ButtonLine
import app.lidory.duckcount.settings.viewmodel.AccountViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    navigateToAccountDeletionScreen: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel: AccountViewModel = koinViewModel()

    val openLogOutDialog = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onLogout() }
    }

    when {
        openLogOutDialog.value -> {
            AlertDialog(
                onDismissRequest = { openLogOutDialog.value = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            openLogOutDialog.value = false
                            viewModel.logOut()
                            onLogout()
                        },
                    ) {
                        Text(text = stringResource(R.string.logout))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { openLogOutDialog.value = false },
                    ) {
                        Text(text = stringResource(androidUiR.string.cancel))
                    }
                },
                title = { Text(text = stringResource(R.string.logout)) },
                text = { Text(text = stringResource(R.string.logout_confirmation)) },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.account)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                val username = viewModel.username ?: ""
                val fullText = stringResource(R.string.connected_as, username)
                val startIndex = fullText.indexOf(username)

                val annotatedText = buildAnnotatedString {
                    append(fullText)
                    if (startIndex >= 0) {
                        addStyle(
                            style = SpanStyle(fontWeight = FontWeight.Bold),
                            start = startIndex,
                            end = startIndex + username.length,
                        )
                    }
                }

                Text(
                    modifier = Modifier.padding(vertical = Padding.large),
                    text = annotatedText,
                )
            }

            item {
                ButtonLine(
                    text = R.string.change_password,
                    onClick = { TODO() },
                )
            }

            item {
                ButtonLine(
                    text = R.string.logout,
                    onClick = { openLogOutDialog.value = !openLogOutDialog.value },
                )
            }

            item {
                ButtonLine(
                    text = R.string.delete_account,
                    onClick = navigateToAccountDeletionScreen,
                )
            }
        }
    }
}
