package app.lidory.duckcount.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.lidory.duckcount.navigation.key.NavigableTabNavKey

@Composable
fun BottomNavigationBar(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey>,
) {
    NavigationBar(
        modifier = modifier,
    ) {
        NavigableTabNavKey.tabs.forEach { item ->
            NavigationBarItem(
                icon = {
                    Icon(
                        painter = item.icon(),
                        contentDescription = item.title(),
                    )
                },
                label = { Text(item.title()) },
                // Checks if the last item in the back stack matches the current item.
                selected = backStack.lastOrNull() == item,
                onClick = { if (backStack.lastOrNull() != item) backStack.add(item) },
            )
        }
    }
}
