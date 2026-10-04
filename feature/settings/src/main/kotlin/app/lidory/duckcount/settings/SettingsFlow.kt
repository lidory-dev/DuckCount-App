package app.lidory.duckcount.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import app.lidory.duckcount.settings.navigation.NavigationRoot

@Composable
fun SettingsFlow(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    startOnNavKey: NavKey? = null,
) {
    NavigationRoot(
        modifier = modifier,
        onBack = onBack,
        startOnNavKey = startOnNavKey,
    )
}
