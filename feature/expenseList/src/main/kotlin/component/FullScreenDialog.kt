package component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import app.lidory.duckcount.android.ui.constant.Padding

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
                .padding(all = Padding.medium)
                .zIndex(1f),
        ) {
            content()
        }
    }
}