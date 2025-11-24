package app.lidory.duckcount.navigation

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
data object ExpenseListScreen : NavKey

@Serializable
data class ExpenseDetailScreen(val expenseUuid: String) : NavKey

@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(ExpenseListScreen)

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key ->
            when (key) {
                is ExpenseListScreen -> {
                    NavEntry(
                        key = key,
                    ) {
                        ExpenseListScreen(
                            onExpenseClicked = { expenseUuid ->
                                backStack.add(
                                    ExpenseDetailScreen(
                                        expenseUuid,
                                    )
                                )
                            },
                        )
                    }
                }

                is ExpenseDetailScreen -> {
                    NavEntry(
                        key = key,
                    ) {
                        ExpenseDetailScreen(
                            viewModel = koinViewModel {
                                parametersOf(key.expenseUuid)
                            },
                            onBack = dropUnlessResumed { backStack.removeLastOrNull() },
                        )
                    }
                }

                else -> throw RuntimeException("Invalid NavKey.")
            }
        }
    )
}
