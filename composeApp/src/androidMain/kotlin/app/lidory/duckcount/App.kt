package app.lidory.duckcount

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
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
            // Brief hold while the start destination is resolved from local storage, which also
            // keeps the onboarding screen from flashing before we know where to go. Painted in
            // the splash color rather than the app background so that if it is ever visible it
            // reads as the splash still being up, not as a second screen.
            Startup.Loading ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colorResource(R.color.splash_background)),
                )

            else -> {
                val backStack = rememberNavBackStack(
                    if (state is Startup.LoggedIn) DashboardKey else OnBoardingScreenKey,
                )

                val context = LocalContext.current
                val isSessionExpired by appViewModel.isSessionExpired.collectAsState()

                // The server has ended the session (the refresh token was turned down).
                LaunchedEffect(isSessionExpired) {
                    if (!isSessionExpired) return@LaunchedEffect

                    Toast.makeText(context, R.string.session_expired, Toast.LENGTH_LONG).show()

                    backStack.add(OnBoardingScreenKey)
                    backStack.removeIf { it !is OnBoardingScreenKey }

                    appViewModel.onSessionExpiryHandled()
                }

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
