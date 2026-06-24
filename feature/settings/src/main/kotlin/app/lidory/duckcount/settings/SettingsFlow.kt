package app.lidory.duckcount.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lidory.duckcount.settings.navigation.NavigationRoot

@Composable
fun SettingsFlow(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    NavigationRoot(
        modifier = modifier,
        onBack = onBack,
        onLogout = onLogout,
    )
}