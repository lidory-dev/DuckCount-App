package app.lidory.duckcount.groupList.tab

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

abstract class Tab {
    @Composable
    abstract fun title(): String

    @Composable
    abstract fun icon(): Painter
}
