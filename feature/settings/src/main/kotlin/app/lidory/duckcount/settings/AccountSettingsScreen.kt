package app.lidory.duckcount.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.settings.component.ButtonLine
import app.lidory.duckcount.settings.viewmodel.AccountSettingsViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    navigateToChangeFullName: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel: AccountSettingsViewModel = koinViewModel()

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onLogout() }
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
                ButtonLine(
                    text = R.string.change_fullName,
                    onClick = navigateToChangeFullName,
                )

                ButtonLine(
                    text = R.string.change_username,
                    onClick = {}, // TODO
                )

                ButtonLine(
                    text = R.string.change_password,
                    onClick = {}, // TODO
                )
            }

            item {
                ButtonLine(
                    text = R.string.logout,
                    onClick = {
                        viewModel.logOut()
                        onLogout()
                    },
                )
            }
        }
    }
}