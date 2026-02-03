package app.lidory.duckcount.navigation.key

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.navigation3.runtime.NavKey

/**
 * Represents a tab that the user can navigate to.
 */
abstract class NavigableTabNavKey : NavKey {
    @Composable
    abstract fun title(): String

    @Composable
    abstract fun icon(): Painter
}
