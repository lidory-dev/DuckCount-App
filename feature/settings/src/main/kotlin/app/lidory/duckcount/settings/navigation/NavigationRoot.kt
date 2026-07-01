package app.lidory.duckcount.settings.navigation

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.settings.AboutScreen
import app.lidory.duckcount.settings.AccountSettingsScreen
import app.lidory.duckcount.settings.LicencesScreen
import app.lidory.duckcount.settings.SettingsScreen
import app.lidory.duckcount.settings.UserProfileScreen
import app.lidory.duckcount.settings.navigation.key.AboutScreenKey
import app.lidory.duckcount.settings.navigation.key.AccountSettingsScreenKey
import app.lidory.duckcount.settings.navigation.key.LicencesScreenKey
import app.lidory.duckcount.settings.navigation.key.SettingsScreenKey
import app.lidory.duckcount.settings.navigation.key.UserProfileScreenKey

@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val backStack = rememberNavBackStack(SettingsScreenKey)

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
            entry<SettingsScreenKey> {
                SettingsScreen(
                    onBack = dropUnlessResumed { onBack() },
                    navigateToUserProfileScreen = {
                        backStack.add(UserProfileScreenKey)
                    },
                    navigateToAccountSettingsScreen = {
                        backStack.add(AccountSettingsScreenKey)
                    },
                    navigateToAboutScreen = {
                        backStack.add(AboutScreenKey)
                    },
                    navigateToLicencesScreen = {
                        backStack.add(LicencesScreenKey)
                    },
                )
            }

            entry<UserProfileScreenKey> {
                UserProfileScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is UserProfileScreenKey }
                    },
                )
            }

            entry<AccountSettingsScreenKey> {
                AccountSettingsScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is AccountSettingsScreenKey }
                    },
                    onLogout = onLogout,
                )
            }

            entry<AboutScreenKey> {
                AboutScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is AboutScreenKey }
                    },
                )
            }

            entry<LicencesScreenKey> {
                LicencesScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is LicencesScreenKey }
                    },
                )
            }
        },
    )
}
