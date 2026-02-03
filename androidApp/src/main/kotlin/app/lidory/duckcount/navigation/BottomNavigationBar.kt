package app.lidory.duckcount.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.lidory.duckcount.navigation.key.ContactListScreenKey
import app.lidory.duckcount.navigation.key.ExpenseListScreenKey
import app.lidory.duckcount.navigation.key.GroupListScreenKey
import app.lidory.duckcount.navigation.key.NavigableTabNavKey

@Composable
fun BottomNavigationBar(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
) {
    val items = listOf(ExpenseListScreenKey, GroupListScreenKey, ContactListScreenKey)

    NavigationBar(
        modifier = modifier,
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = {
                    Icon(
                        painter = item.icon(),
                        contentDescription = item.title(),
                    )
                },
                label = { Text(item.title()) },
                // Checks if the last navigable tab in the back stack matches the current item.
                selected = backStack.lastOrNull { it is NavigableTabNavKey } == items[index],
                onClick = { backStack.add(item) },
            )
        }
    }
}
