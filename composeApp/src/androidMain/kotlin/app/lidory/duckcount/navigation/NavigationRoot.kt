package app.lidory.duckcount.navigation

import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.ExpenseDetailScreen
import app.lidory.duckcount.ExpenseListScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
data object ExpenseListScreenKey : NavKey

@Serializable
data class ExpenseDetailScreenKey(val expenseUuid: String) : NavKey

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(ExpenseListScreenKey)

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

                else -> throw RuntimeException("Invalid NavKey.")
            }
        }
    )
}
