package app.lidory.duckcount.navigation

import ContactDetailScreen
import NewContactScreen
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
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.android.ui.theme.isAppInDarkTheme
import app.lidory.duckcount.expenseList.CreateExpenseScreen
import app.lidory.duckcount.expenseList.ExpenseDetailScreen
import app.lidory.duckcount.expenseList.ExpenseListScreen
import app.lidory.duckcount.expenseList.viewmodel.expenseListViewModel
import app.lidory.duckcount.groupList.CreateGroupScreen
import app.lidory.duckcount.groupList.GroupBalanceScreen
import app.lidory.duckcount.groupList.GroupDetailScreen
import app.lidory.duckcount.navigation.key.ContactDetailScreenKey
import app.lidory.duckcount.navigation.key.CreateExpenseScreenKey
import app.lidory.duckcount.navigation.key.CreateGroupScreenKey
import app.lidory.duckcount.navigation.key.DashboardKey
import app.lidory.duckcount.navigation.key.ExpenseDetailScreenKey
import app.lidory.duckcount.navigation.key.GroupDetailScreenKey
import app.lidory.duckcount.navigation.key.NewContactScreenKey
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf


@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
) {
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        sceneStrategy = listDetailStrategy,
        entryProvider = { key ->
            when (key) {
                is DashboardKey -> {
                    NavEntry(
                        key = key,
                    ) {
                        Dashboard(
                            navigateToExpenseDetailScreen = { expenseUuid ->
                                backStack.add(
                                    ExpenseDetailScreenKey(
                                        expenseUuid,
                                    ),
                                )
                            },
                            navigateToCreateExpenseScreen = {
                                backStack.add(
                                    CreateExpenseScreenKey(groupUuid = null),
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
                                    NewContactScreenKey(),
                                )
                            },
                            navigateToCreateGroupScreen = {
                                backStack.add(
                                    CreateGroupScreenKey(),
                                )
                            },
                            navigateToGroupDetailScreen = { groupUuid ->
                                backStack.add(
                                    GroupDetailScreenKey(
                                        groupUuid,
                                    ),
                                )
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
                        )
                    }
                }

                is CreateExpenseScreenKey -> {
                    NavEntry(
                        key = key,
                    ) {
                        CreateExpenseScreen(
                            viewModel = koinViewModel {
                                parametersOf(key.groupUuid)
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

                is CreateGroupScreenKey -> {
                    NavEntry(
                        key = key,
                    ) {
                        CreateGroupScreen(
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
                            navigateToCreateExpenseGroup = {
                                backStack.add(CreateExpenseScreenKey(groupUuid = key.groupUuid))
                            },
                            expenseListContent = {
                                ExpenseListScreen(
                                    onExpenseClicked = { expenseUuid ->
                                        backStack.add(ExpenseDetailScreenKey(expenseUuid = expenseUuid))
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
                        )
                    }
                }

                else -> throw RuntimeException("Invalid NavKey.")
            }
        },
    )
}
