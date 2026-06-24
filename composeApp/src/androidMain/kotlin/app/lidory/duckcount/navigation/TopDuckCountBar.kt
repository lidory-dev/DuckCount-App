package app.lidory.duckcount.navigation

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopDuckCountBar(
    modifier: Modifier = Modifier,
    @StringRes title: Int,
    navigateToSettingsScreen: () -> Unit,
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(text = stringResource(title)) },
        actions = {
            IconButton(
                onClick = navigateToSettingsScreen,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings),
                )
            }
        },
    )
}