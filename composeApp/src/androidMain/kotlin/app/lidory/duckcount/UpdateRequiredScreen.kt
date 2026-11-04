package app.lidory.duckcount

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.composable.NoticeScreen

/**
 * What the whole app is once the server has refused this build, see [AppViewModel.isUpdateRequired],
 * with no way past it: the server answers nothing else this build asks.
 */
@Composable
fun UpdateRequiredScreen() {
    val uriHandler = LocalUriHandler.current
    val packageName = LocalContext.current.packageName

    NoticeScreen(
        title = stringResource(R.string.update_required_title),
        message = stringResource(R.string.update_required_message),
        buttonText = stringResource(R.string.update_required_button),
        onButtonClick = {
            // A phone with no store to take the first has a browser to take the second.
            // The handler reports an activity not found as an IllegalArgumentException.
            try {
                uriHandler.openUri("market://details?id=$packageName")
            } catch (_: IllegalArgumentException) {
                uriHandler.openUri("https://play.google.com/store/apps/details?id=$packageName")
            }
        },
    )
}
