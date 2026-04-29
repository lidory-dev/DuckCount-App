package app.lidory.duckcount.navigation

import ContactListScreen
import ExpenseListScreen
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
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.navigation.key.ContactListScreenKey
import app.lidory.duckcount.navigation.key.ExpenseListScreenKey
import app.lidory.duckcount.navigation.key.GroupListScreenKey
import component.AddContactFloatingActionButton
import component.AddExpenseFloatingActionButton

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun Dashboard(
    modifier: Modifier = Modifier,
    navigateToExpenseDetailScreen: (String) -> Unit,
    navigateToCreateExpenseScreen: () -> Unit,
    navigateToContactDetailScreen: (String) -> Unit,
    navigateToCreateContact: () -> Unit,
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

                is ContactListScreenKey -> {
                    AddContactFloatingActionButton(
                        onClick = navigateToCreateContact,
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
                            Text(key.title())
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
