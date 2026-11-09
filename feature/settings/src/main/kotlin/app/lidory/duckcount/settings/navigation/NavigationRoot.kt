package app.lidory.duckcount.settings.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.android.ui.animation.directedTransition
import app.lidory.duckcount.android.ui.animation.popTransition
import app.lidory.duckcount.android.ui.animation.rememberBackStackDirection
import app.lidory.duckcount.settings.AboutScreen
import app.lidory.duckcount.settings.AccountDeletionScreen
import app.lidory.duckcount.settings.AccountScreen
import app.lidory.duckcount.settings.LicencesScreen
import app.lidory.duckcount.settings.PasswordChangeScreen
import app.lidory.duckcount.settings.ReserveDiscoveryUsernameScreen
import app.lidory.duckcount.settings.SettingsScreen
import app.lidory.duckcount.settings.UserProfileScreen
import app.lidory.duckcount.settings.navigation.key.AboutScreenKey
import app.lidory.duckcount.settings.navigation.key.AccountDeletionScreenKey
import app.lidory.duckcount.settings.navigation.key.AccountScreenKey
import app.lidory.duckcount.settings.navigation.key.LicencesScreenKey
import app.lidory.duckcount.settings.navigation.key.PasswordChangeScreenKey
import app.lidory.duckcount.settings.navigation.key.ReserveDiscoveryUsernameScreenKey
import app.lidory.duckcount.settings.navigation.key.SettingsScreenKey
import app.lidory.duckcount.settings.navigation.key.UserProfileScreenKey

@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    startOnNavKey: NavKey? = null,
) {
    // Landing straight on the startOnNavKey keeps the settings list underneath,
    // so backing out of it still leads somewhere sensible.
    val backStack = rememberNavBackStack(
        *if (startOnNavKey != null) {
            arrayOf(SettingsScreenKey, startOnNavKey)
        } else {
            arrayOf(SettingsScreenKey)
        },
    )

    // Set as the password change closes on success, for the account screen it closes onto to
    // say so: it has no confirmation of its own, the change being the only thing it is for.
    var isPasswordChanged by rememberSaveable { mutableStateOf(false) }

    val backStackDirection = rememberBackStackDirection(backStack)

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { directedTransition(backStackDirection) },
        popTransitionSpec = { directedTransition(backStackDirection) },
        predictivePopTransitionSpec = { popTransition() },
        entryProvider = entryProvider {
            entry<SettingsScreenKey> {
                SettingsScreen(
                    onBack = dropUnlessResumed { onBack() },
                    navigateToReserveDiscoveryUsernameScreen = {
                        backStack.add(ReserveDiscoveryUsernameScreenKey())
                    },
                    navigateToUserProfileScreen = {
                        backStack.add(UserProfileScreenKey)
                    },
                    navigateToAccountScreen = {
                        backStack.add(AccountScreenKey)
                    },
                    navigateToAboutScreen = {
                        backStack.add(AboutScreenKey)
                    },
                    navigateToLicencesScreen = {
                        backStack.add(LicencesScreenKey)
                    },
                )
            }

            entry<ReserveDiscoveryUsernameScreenKey> {
                ReserveDiscoveryUsernameScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is ReserveDiscoveryUsernameScreenKey }
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

            entry<AccountScreenKey> {
                AccountScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is AccountScreenKey }
                    },
                    navigateToPasswordChangeScreen = {
                        backStack.add(PasswordChangeScreenKey)
                    },
                    navigateToAccountDeletionScreen = {
                        backStack.add(AccountDeletionScreenKey)
                    },
                    isPasswordChanged = isPasswordChanged,
                    onPasswordChangedShown = { isPasswordChanged = false },
                )
            }

            entry<PasswordChangeScreenKey> {
                PasswordChangeScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is PasswordChangeScreenKey }
                    },
                    onPasswordChanged = dropUnlessResumed {
                        isPasswordChanged = true
                        backStack.removeIf { it is PasswordChangeScreenKey }
                    },
                )
            }

            entry<AccountDeletionScreenKey> {
                AccountDeletionScreen(
                    onBack = dropUnlessResumed {
                        backStack.removeIf { it is AccountDeletionScreenKey }
                    },
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
