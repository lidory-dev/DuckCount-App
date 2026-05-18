package app.lidory.duckcount.android.ui.composable.fullscreendialog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex

@Composable
fun FullScreenDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    AnimatedVisibility(
        visible = isVisible,
        modifier = Modifier.zIndex(1f),
    ) {
        BackHandler(onBack = onDismissRequest)

        Surface(
            modifier = modifier
                .fillMaxSize()
                .zIndex(1f),
        ) {
            content()
        }
    }
}
