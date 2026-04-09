package navigation

import LoginScreen
import OnBoardingScreen
import PasswordLeakScreen
import PendingProfile
import RegisterScreen
import SetUpProfileScreen
import androidx.annotation.StringRes
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import navigation.key.LoginScreenKey
import navigation.key.OnBoardingScreenKey
import navigation.key.PasswordLeakScreenKey
import navigation.key.RegisterScreenKey
import navigation.key.SetUpProfileScreenKey

@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
    @StringRes appName: Int,
    @StringRes onBoardingTitle: Int,
    @StringRes onBoardingDescription: Int,
    startResume: PendingProfile?,
    navigateToDashboard: () -> Unit,
) {
    // When resuming an interrupted registration, open straight on the "set up profile" step.
    val backStack = rememberNavBackStack(
        *buildList {
            if (startResume == null) {
                add(OnBoardingScreenKey)
            } else {
                add(
                    SetUpProfileScreenKey(
                        uuid = startResume.uuid,
                        username = startResume.username,
                        hasPasswordBeenChecked = startResume.hasPasswordBeenChecked,
                    ),
                )
            }
        }.toTypedArray(),
    )

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = {
            slideInHorizontally(initialOffsetX = { it }) togetherWith slideOutHorizontally(targetOffsetX = { -it })
        },
        popTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it }) togetherWith slideOutHorizontally(targetOffsetX = { it })
        },
        predictivePopTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it }) togetherWith slideOutHorizontally(targetOffsetX = { it })
        },
        entryProvider = entryProvider {
            entry<OnBoardingScreenKey> {
                OnBoardingScreen(
                    appName = appName,
                    onBoardingTitle = onBoardingTitle,
                    onBoardingDescription = onBoardingDescription,
                    navigateToLoginScreen = { backStack.add(LoginScreenKey) },
                    navigateToRegisterScreen = { backStack.add(RegisterScreenKey) },
                )
            }
            entry<LoginScreenKey> {
                LoginScreen(
                    navigateBack = { backStack.removeAt(backStack.lastIndex) },
                    navigateToDashboard = navigateToDashboard,
                )
            }
            entry<RegisterScreenKey> {
                RegisterScreen(
                    navigateBack = { backStack.removeAt(backStack.lastIndex) },
                    navigateToLeakPasswordScreen = { leakAmount ->
                        backStack.add(PasswordLeakScreenKey(leakAmount = leakAmount))
                    },
                    navigateToSetUpProfileScreen = { uuid, username, hasPasswordBeenChecked ->
                        backStack.add(
                            SetUpProfileScreenKey(
                                uuid = uuid,
                                username = username,
                                hasPasswordBeenChecked = hasPasswordBeenChecked,
                            ),
                        )
                        backStack.removeIf { it !is SetUpProfileScreenKey }
                    },
                )
            }
            entry<PasswordLeakScreenKey> { key ->
                PasswordLeakScreen(
                    leakAmount = key.leakAmount,
                    navigateBack = { backStack.removeAt(backStack.lastIndex) },
                )
            }
            entry<SetUpProfileScreenKey> { key ->
                SetUpProfileScreen(
                    uuid = key.uuid,
                    username = key.username,
                    hasPasswordBeenChecked = key.hasPasswordBeenChecked,
                    navigateToDashboard = navigateToDashboard,
                )
            }
        },
    )
}
