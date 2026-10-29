package app.lidory.duckcount.navigation

import AuthenticationFlow
import ContactDetailScreen
import ContactRequestListScreen
import ContactVerificationScreen
import NewContactScreen
import PendingProfile
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.R
import app.lidory.duckcount.android.ui.animation.popTransition
import app.lidory.duckcount.android.ui.animation.pushTransition
import app.lidory.duckcount.android.ui.animation.replaceTransition
import app.lidory.duckcount.android.ui.theme.isAppInDarkTheme
import app.lidory.duckcount.authentication.KeyPackageSetUp
import app.lidory.duckcount.expenseList.CreateExpenseScreen
import app.lidory.duckcount.expenseList.ExpenseDetailScreen
import app.lidory.duckcount.expenseList.ExpenseHistoryDetailScreen
import app.lidory.duckcount.expenseList.ExpenseHistoryScreen
import app.lidory.duckcount.expenseList.ExpenseListScreen
import app.lidory.duckcount.expenseList.viewmodel.expenseListViewModel
import app.lidory.duckcount.groupList.CreateOrEditGroupScreen
import app.lidory.duckcount.groupList.GroupBalanceScreen
import app.lidory.duckcount.groupList.GroupDetailScreen
import app.lidory.duckcount.groupList.GroupHistoryScreen
import app.lidory.duckcount.groupList.GroupSettingsScreen
import app.lidory.duckcount.groupList.GroupSettleUpScreen
import app.lidory.duckcount.navigation.key.ContactDetailScreenKey
import app.lidory.duckcount.navigation.key.ContactRequestListScreenKey
import app.lidory.duckcount.navigation.key.ContactVerificationScreenKey
import app.lidory.duckcount.navigation.key.CreateExpenseScreenKey
import app.lidory.duckcount.navigation.key.CreateGroupScreenKey
import app.lidory.duckcount.navigation.key.DashboardKey
import app.lidory.duckcount.navigation.key.ExpenseDetailScreenKey
import app.lidory.duckcount.navigation.key.ExpenseHistoryDetailScreenKey
import app.lidory.duckcount.navigation.key.ExpenseHistoryScreenKey
import app.lidory.duckcount.navigation.key.GroupDetailScreenKey
import app.lidory.duckcount.navigation.key.GroupHistoryScreenKey
import app.lidory.duckcount.navigation.key.GroupSettingsScreenKey
import app.lidory.duckcount.navigation.key.NewContactScreenKey
import app.lidory.duckcount.settings.SettingsFlow
import app.lidory.duckcount.settings.navigation.key.ReserveDiscoveryUsernameScreenKey
import app.lidory.duckcount.settings.navigation.key.SettingsFlowKey
import navigation.key.OnBoardingScreenKey
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import app.lidory.duckcount.push.PushSetUp
import app.lidory.duckcount.push.PushTearDown

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
    resumeRegistration: PendingProfile? = null,
    openGroups: Boolean = false,
) {
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    SharedTransitionLayout {
        NavDisplay(
            modifier = modifier,
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            sceneStrategy = listDetailStrategy,
            transitionSpec = { pushTransition() },
            popTransitionSpec = { popTransition() },
            predictivePopTransitionSpec = { popTransition() },
            entryProvider = { key ->
                when (key) {
                    is DashboardKey -> {
                        NavEntry(
                            key = key,
                            // Signing in replaces the signed-out app with it rather than opening
                            // it on top: there is nothing to slide back to.
                            metadata = NavDisplay.transitionSpec { replaceTransition() },
                        ) {
                            // Here rather than keyed off the launch state: `Startup` is
                            // resolved once, when the app opens, and says where to land.
                            // Somebody who opens the app signed out and then signs in never
                            // changes it, so gating on it meant a device registered for push
                            // on launch or not at all, sign in, and nothing could wake you
                            // until you restarted the app. The dashboard is the signed-in
                            // app, however the user arrived at it.
                            PushSetUp()
                            KeyPackageSetUp()

                            Dashboard(
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                openGroups = openGroups,
                                navigateToExpenseDetailScreen = { expenseUuid, groupUuid ->
                                    backStack.add(
                                        ExpenseDetailScreenKey(
                                            groupUuid = groupUuid,
                                            expenseUuid = expenseUuid,
                                        ),
                                    )
                                },
                                navigateToCreateExpenseScreen = {
                                    backStack.add(
                                        CreateExpenseScreenKey(groupUuid = null, expenseUuid = null),
                                    )
                                },
                                navigateToContactDetailScreen = { userUuid ->
                                    backStack.add(
                                        ContactDetailScreenKey(
                                            userUuid,
                                        ),
                                    )
                                },
                                navigateToNewContact = {
                                    backStack.add(
                                        NewContactScreenKey,
                                    )
                                },
                                navigateToContactRequests = {
                                    backStack.add(
                                        ContactRequestListScreenKey,
                                    )
                                },
                                navigateToCreateGroupScreen = {
                                    backStack.add(
                                        CreateGroupScreenKey(
                                            groupUuid = null,
                                        ),
                                    )
                                },
                                navigateToGroupDetailScreen = { groupUuid ->
                                    backStack.add(
                                        GroupDetailScreenKey(
                                            groupUuid,
                                        ),
                                    )
                                },
                                navigateToSettingsScreen = {
                                    backStack.add(SettingsFlowKey())
                                },
                                navigateToUsernameReservation = {
                                    backStack.add(
                                        SettingsFlowKey(startOnNavKey = ReserveDiscoveryUsernameScreenKey()),
                                    )
                                },
                            )
                        }
                    }

                    is SettingsFlowKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            SettingsFlow(
                                startOnNavKey = key.startOnNavKey,
                                onBack = {
                                    backStack.removeIf { it is SettingsFlowKey }
                                },
                            )
                        }
                    }

                    is ExpenseDetailScreenKey -> {
                        NavEntry(
                            key = key,
                            metadata = ListDetailSceneStrategy.detailPane(),
                        ) {
                            ExpenseDetailScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.expenseUuid)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is ExpenseDetailScreenKey } },
                                navigateToEditExpense = {
                                    backStack.add(
                                        CreateExpenseScreenKey(
                                            groupUuid = key.groupUuid,
                                            expenseUuid = key.expenseUuid,
                                        ),
                                    )
                                },
                                navigateToExpenseHistory = {
                                    backStack.add(ExpenseHistoryScreenKey(expenseUuid = key.expenseUuid))
                                },
                            )
                        }
                    }

                    is CreateExpenseScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            CreateExpenseScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.groupUuid, key.expenseUuid)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is CreateExpenseScreenKey } },
                            )
                        }
                    }

                    is ContactDetailScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            ContactDetailScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.userUuid)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is ContactDetailScreenKey } },
                                navigateToContactVerification = {
                                    backStack.add(ContactVerificationScreenKey(userUuid = key.userUuid))
                                },
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                sharedTransitionScope = this@SharedTransitionLayout,
                            )
                        }
                    }

                    is ContactVerificationScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            ContactVerificationScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.userUuid)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is ContactVerificationScreenKey } },
                            )
                        }
                    }

                    is OnBoardingScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            // The signed-out app, as the dashboard is the signed-in one: the
                            // process a log-out, an account deletion or an expiry relaunches
                            // into lands here.
                            PushTearDown()

                            AuthenticationFlow(
                                appName = R.string.app_name,
                                onBoardingTitle = R.string.on_boarding_title,
                                onBoardingDescription = R.string.on_boarding_description,
                                startResume = resumeRegistration,
                                navigateToDashboard = {
                                    backStack.add(DashboardKey)
                                    backStack.remove(OnBoardingScreenKey)
                                },
                            )
                        }
                    }

                    is NewContactScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            NewContactScreen(
                                onBack = dropUnlessResumed { backStack.removeIf { it is NewContactScreenKey } },
                            )
                        }
                    }

                    is ContactRequestListScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            ContactRequestListScreen(
                                onBack = dropUnlessResumed { backStack.removeIf { it is ContactRequestListScreenKey } },
                            )
                        }
                    }

                    is CreateGroupScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            CreateOrEditGroupScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.groupUuid)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is CreateGroupScreenKey } },
                            )
                        }
                    }

                    is GroupDetailScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            GroupDetailScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.groupUuid)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is GroupDetailScreenKey } },
                                navigateToCreateGroupExpense = {
                                    backStack.add(
                                        CreateExpenseScreenKey(
                                            groupUuid = key.groupUuid,
                                            expenseUuid = null,
                                        ),
                                    )
                                },
                                navigateToGroupSettings = {
                                    backStack.add(GroupSettingsScreenKey(key.groupUuid))
                                },
                                expenseListContent = {
                                    ExpenseListScreen(
                                        onExpenseClicked = { expenseUuid, groupUuid ->
                                            backStack.add(
                                                ExpenseDetailScreenKey(
                                                    expenseUuid = expenseUuid,
                                                    groupUuid = groupUuid,
                                                ),
                                            )
                                        },
                                        viewModel = expenseListViewModel(key.groupUuid),
                                    )
                                },
                                balanceContent = {
                                    GroupBalanceScreen(
                                        viewModel = koinViewModel {
                                            parametersOf(key.groupUuid)
                                        },
                                        isAppInDarkTheme = isAppInDarkTheme(),
                                    )
                                },
                                settleUpContent = {
                                    GroupSettleUpScreen(
                                        viewModel = koinViewModel {
                                            parametersOf(key.groupUuid)
                                        },
                                    )
                                },
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                sharedTransitionScope = this@SharedTransitionLayout,
                            )
                        }
                    }

                    is GroupSettingsScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            GroupSettingsScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.groupUuid)
                                },
                                onBack = { backStack.removeIf { it is GroupSettingsScreenKey } },
                                navigateToEditGroup = { backStack.add(CreateGroupScreenKey(key.groupUuid)) },
                                navigateToGroupHistory = { backStack.add(GroupHistoryScreenKey(key.groupUuid)) },
                                navigateToContactDetail = { userUuid ->
                                    backStack.add(ContactDetailScreenKey(userUuid))
                                },
                                navigateToContactRequests = { backStack.add(ContactRequestListScreenKey) },
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                sharedTransitionScope = this@SharedTransitionLayout,
                            )
                        }
                    }

                    is GroupHistoryScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            GroupHistoryScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.groupUuid)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is GroupHistoryScreenKey } },
                            )
                        }
                    }

                    is ExpenseHistoryScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            ExpenseHistoryScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.expenseUuid)
                                },
                                navigateToHistoryDetail = { revision ->
                                    backStack.add(
                                        ExpenseHistoryDetailScreenKey(
                                            expenseUuid = key.expenseUuid,
                                            revision = revision,
                                        ),
                                    )
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is ExpenseHistoryScreenKey } },
                            )
                        }
                    }

                    is ExpenseHistoryDetailScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            ExpenseHistoryDetailScreen(
                                viewModel = koinViewModel {
                                    parametersOf(key.expenseUuid, key.revision)
                                },
                                onBack = dropUnlessResumed { backStack.removeIf { it is ExpenseHistoryDetailScreenKey } },
                            )
                        }
                    }

                    else -> throw RuntimeException("Invalid NavKey.")
                }
            },
        )
    }
}
