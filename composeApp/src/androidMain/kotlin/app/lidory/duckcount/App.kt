package app.lidory.duckcount

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.rememberNavBackStack
import app.lidory.duckcount.android.ui.theme.AppTheme
import app.lidory.duckcount.navigation.NavigationRoot
import app.lidory.duckcount.navigation.key.DashboardKey

@Composable
@Preview
fun App() {
    AppTheme {
        val backStack = rememberNavBackStack(DashboardKey)

        NavigationRoot(
            modifier = Modifier
                .fillMaxSize(),
            backStack = backStack,
        )
    }
}
