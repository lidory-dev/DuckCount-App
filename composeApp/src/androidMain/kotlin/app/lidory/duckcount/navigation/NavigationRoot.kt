package app.lidory.duckcount.navigation

import ExpenseDetailScreen
import ExpenseListScreen
import androidx.compose.material3.Text
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
import app.lidory.duckcount.navigation.key.ContactListScreenKey
import app.lidory.duckcount.navigation.key.ExpenseDetailScreenKey
import app.lidory.duckcount.navigation.key.ExpenseListScreenKey
import app.lidory.duckcount.navigation.key.GroupListScreenKey
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
                is ExpenseListScreenKey -> {
                    NavEntry(
                        key = key,
                        metadata = ListDetailSceneStrategy.listPane(
                            detailPlaceholder = {
                                Text("Choose an expense to display more information about it.")
                            },
                        )
                    ) {
                        ExpenseListScreen(
                            onExpenseClicked = { expenseUuid ->
                                backStack.add(
                                    ExpenseDetailScreenKey(
                                        expenseUuid,
                                    )
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
                        Text(key.title())
                    }
                }

                else -> throw RuntimeException("Invalid NavKey.")
            }
        }
    )
}
