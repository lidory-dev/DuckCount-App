package app.lidory.duckcount

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.rememberNavBackStack
import app.lidory.duckcount.android.ui.theme.AppTheme
import app.lidory.duckcount.navigation.NavigationRoot
import app.lidory.duckcount.navigation.key.DashboardKey
import navigation.key.OnBoardingScreenKey
import org.koin.androidx.compose.koinViewModel

@Composable
fun App() {
    AppTheme {
        val appViewModel = koinViewModel<AppViewModel>()
        val startup by appViewModel.startup.collectAsState()

        when (val state = startup) {
            // Brief theme-colored hold while the start destination is resolved from local
            // storage. This is what prevents the onboarding screen from flashing before we
            // know where to go.
            Startup.Loading ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )

            else -> {
                val backStack = rememberNavBackStack(
                    if (state is Startup.LoggedIn) DashboardKey else OnBoardingScreenKey,
                )

                NavigationRoot(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    backStack = backStack,
                    resumeRegistration = (state as? Startup.ResumeRegistration)?.pending,
                )
            }
        }
    }
}
