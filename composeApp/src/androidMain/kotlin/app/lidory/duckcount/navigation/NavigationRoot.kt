package app.lidory.duckcount.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
data object ExpenseListScreen : NavKey

@Serializable
data class ExpenseDetailScreen(val id: String) : NavKey

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
                        Column {
                            Text("ExpenseListScreen")

                            Button(
                                onClick = dropUnlessResumed { backStack.add(ExpenseDetailScreen("3")) },
                            ) {
                                Text("Go to ExpenseDetailScreen")
                            }
                        }
                    }
                }

                is ExpenseDetailScreen -> {
                    NavEntry(
                        key = key,
                    ) {
                        Column {
                            Text("ExpenseDetailScreen ${key.id}")

                            Button(
                                onClick = dropUnlessResumed { backStack.removeLastOrNull() },
                            ) {
                                Text("Go back to ExpenseListScreen")
                            }
                        }
                    }
                }

                else -> throw RuntimeException("Invalid NavKey.")
            }
        }
    )
}
