package app.lidory.duckcount.android.ui.composable.textfield

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object DuckCountTextFieldDefault {
    fun contentPaddingWithLabel(
        start: Dp = 16.dp,
        top: Dp = 16.dp,
        end: Dp = 10.dp,
        bottom: Dp = 10.dp,
    ): PaddingValues = PaddingValues(start, top, end, bottom)
}