import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import navigation.NavigationRoot

/**
 * Self-contained authentication flow (onboarding → login/register → set up profile), with its own
 * nested navigation. When [startResume] is non-null the flow opens directly on the " set up profile" step
 * to finish an interrupted registration. Calls [navigateToDashboard] once the user is
 * authenticated so the host app can leave the auth flow.
 */
@Composable
fun AuthenticationFlow(
    modifier: Modifier = Modifier,
    @StringRes appName: Int,
    @StringRes onBoardingTitle: Int,
    @StringRes onBoardingDescription: Int,
    startResume: PendingProfile?,
    navigateToDashboard: () -> Unit,
) {
    NavigationRoot(
        modifier = modifier,
        appName = appName,
        onBoardingTitle = onBoardingTitle,
        onBoardingDescription = onBoardingDescription,
        startResume = startResume,
        navigateToDashboard = navigateToDashboard,
    )
}
