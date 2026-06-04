package app.lidory.duckcount.navigation

import ContactListScreen
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.expenseList.ExpenseListScreen
import app.lidory.duckcount.expenseList.component.AddExpenseFloatingActionButton
import app.lidory.duckcount.groupList.GroupListScreen
import app.lidory.duckcount.groupList.component.AddGroupFloatingActionButton
import app.lidory.duckcount.navigation.key.ContactListScreenKey
import app.lidory.duckcount.navigation.key.ExpenseListScreenKey
import app.lidory.duckcount.navigation.key.GroupListScreenKey
import app.lidory.duckcount.navigation.key.NavigableTabNavKey
import component.AddContactFloatingActionButton

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun Dashboard(
    modifier: Modifier = Modifier,
    navigateToExpenseDetailScreen: (String, String?) -> Unit,
    navigateToCreateExpenseScreen: () -> Unit,
    navigateToContactDetailScreen: (String) -> Unit,
    navigateToNewContact: () -> Unit,
    navigateToCreateGroupScreen: () -> Unit,
    navigateToGroupDetailScreen: (String) -> Unit,
) {
    val backStack = rememberNavBackStack(ExpenseListScreenKey)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            when (backStack.lastOrNull()) {
                is ExpenseListScreenKey -> {
                    AddExpenseFloatingActionButton(
                        onClick = navigateToCreateExpenseScreen,
                    )
                }

                is GroupListScreenKey -> {
                    AddGroupFloatingActionButton(
                        onClick = navigateToCreateGroupScreen,
                    )
                }

                is ContactListScreenKey -> {
                    AddContactFloatingActionButton(
                        onClick = navigateToNewContact,
                    )
                }
            }
        },
        bottomBar = {
            BottomNavigationBar(
                backStack = backStack,
            )
        },
    ) { innerPadding ->
        NavDisplay(
            modifier = modifier.padding(innerPadding),
            backStack = backStack,
            transitionSpec = {
                val direction = getTransitionSpecDirection(initialState, targetState)
                slideIntoContainer(direction, tween()) togetherWith slideOutOfContainer(direction, tween())
            },
            popTransitionSpec = {
                val direction = getTransitionSpecDirection(initialState, targetState)
                slideIntoContainer(direction, tween()) togetherWith slideOutOfContainer(direction, tween())
            },
            predictivePopTransitionSpec = {
                val direction = getTransitionSpecDirection(initialState, targetState)
                slideIntoContainer(direction, tween()) togetherWith slideOutOfContainer(direction, tween())
            },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { key ->
                when (key) {
                    is ExpenseListScreenKey -> {
                        NavEntry(
                            key = key,
                            metadata = ListDetailSceneStrategy.listPane(
                                detailPlaceholder = {
                                    Text("Choose an expense to display more information about it.")
                                },
                            ),
                        ) {
                            ExpenseListScreen(
                                onExpenseClicked = navigateToExpenseDetailScreen,
                            )
                        }
                    }

                    is GroupListScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            GroupListScreen(
                                onGroupClicked = navigateToGroupDetailScreen,
                            )
                        }
                    }

                    is ContactListScreenKey -> {
                        NavEntry(
                            key = key,
                        ) {
                            ContactListScreen(
                                navigateToDetailScreen = navigateToContactDetailScreen,
                            )
                        }
                    }

                    else -> throw RuntimeException("Invalid NavKey.")
                }
            },
        )
    }
}

fun getTransitionSpecDirection(
    initialState: Scene<NavKey>,
    targetState: Scene<NavKey>,
): AnimatedContentTransitionScope.SlideDirection {
    val tabs = NavigableTabNavKey.tabs.map { it.toString() }
    val initialIndex = tabs.indexOf(initialState.key)
    val targetIndex = tabs.indexOf(targetState.key)

    val direction = if (initialIndex < targetIndex) {
        AnimatedContentTransitionScope.SlideDirection.Start
    } else {
        AnimatedContentTransitionScope.SlideDirection.End
    }

    return direction
}