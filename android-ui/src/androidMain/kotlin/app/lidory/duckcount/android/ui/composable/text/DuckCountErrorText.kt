package app.lidory.duckcount.android.ui.composable.text

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Why what the user asked of a screen failed, brought into view as it appears and whenever it
 * changes: in landscape or with a large font, it can appear below what the screen shows, and
 * nothing else on screen tells the user that it failed.
 *
 * Only a scrolling container brings anything into view, so the screen showing it scrolls.
 */
@Composable
fun DuckCountErrorText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(text) {
        bringIntoViewRequester.bringIntoView()
    }

    Text(
        // Outermost, so that what is brought into view includes the room left around it.
        modifier = Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(modifier),
        text = text,
        color = MaterialTheme.colorScheme.error,
    )
}
