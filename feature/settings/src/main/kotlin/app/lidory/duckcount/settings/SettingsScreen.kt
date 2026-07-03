package app.lidory.duckcount.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.settings.component.ButtonLine
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    navigateToUserProfileScreen: () -> Unit,
    navigateToAccountScreen: () -> Unit,
    navigateToAboutScreen: () -> Unit,
    navigateToLicencesScreen: () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(androidUiR.string.settings)) },
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
                    text = R.string.profile,
                    icon = R.drawable.ic_profile,
                    onClick = navigateToUserProfileScreen,
                )
            }

            item {
                ButtonLine(
                    text = R.string.account,
                    icon = R.drawable.ic_account,
                    onClick = navigateToAccountScreen,
                )
            }

            item {
                HorizontalDivider(Modifier, DividerDefaults.Thickness)
            }

            item {
                ButtonLine(
                    text = R.string.about,
                    icon = R.drawable.ic_help,
                    onClick = navigateToAboutScreen,
                )
            }

            item {
                ButtonLine(
                    text = R.string.licences,
                    icon = R.drawable.ic_license,
                    onClick = navigateToLicencesScreen,
                )
            }
        }
    }
}
