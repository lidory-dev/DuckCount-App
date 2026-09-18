package app.lidory.duckcount

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
import app.lidory.duckcount.navigation.key.ContactRequestListScreenKey
import app.lidory.duckcount.navigation.key.DashboardKey
import app.lidory.duckcount.push.PushSetUp
import navigation.key.OnBoardingScreenKey
import org.koin.androidx.compose.koinViewModel

/**
 * [openContactRequests] comes from a notification about a request waiting to be
 * answered, and is what takes the user straight to it.
 */
@Composable
fun App(openContactRequests: Boolean = false) {
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

                if (state is Startup.LoggedIn) {
                    PushSetUp()

                    // Pushed on top of the dashboard rather than instead of it, so that
                    // going back from the requests lands where the app would have opened.
                    LaunchedEffect(Unit) {
                        if (openContactRequests) backStack.add(ContactRequestListScreenKey)
                    }
                }

                // The server has ended the session (the refresh token was turned down). Like
                // a log-out, that takes a new process, which also lands on the onboarding
                // screen and tells the user why.
                LaunchedEffect(isSessionExpired) {
                    if (!isSessionExpired) return@LaunchedEffect

                    context.restartApp(sessionExpired = true)
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
