package app.lidory.duckcount.android.ui.composable

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The snackbars of [hostState], laid out in the screen's own column, under what scrolls and
 * above whatever stays at the bottom, rather than in the scaffold's slot, which lays them over
 * the screen: over a button, or the last rows of a list, they would take the taps meant for
 * them. What scrolls gives up the room while one shows, so nothing ends up under it.
 *
 * Centred, as the scaffold does once the screen is wider than a snackbar.
 */
@Composable
fun ColumnScope.DuckCountSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        modifier = modifier.align(Alignment.CenterHorizontally),
        hostState = hostState,
    )
}
