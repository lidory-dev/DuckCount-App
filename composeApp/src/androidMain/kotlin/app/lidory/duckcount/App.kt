package app.lidory.duckcount

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.rememberNavBackStack
import app.lidory.duckcount.android.ui.theme.AppTheme
import app.lidory.duckcount.navigation.BottomNavigationBar
import app.lidory.duckcount.navigation.NavigationRoot
import app.lidory.duckcount.navigation.key.ExpenseListScreenKey

@Composable
@Preview
fun App() {
    AppTheme {
        val backStack = rememberNavBackStack(ExpenseListScreenKey)

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                BottomNavigationBar(
                    backStack = backStack,
                )
            },
        ) { innerPadding ->
            NavigationRoot(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                backStack = backStack,
            )
        }
    }
}
