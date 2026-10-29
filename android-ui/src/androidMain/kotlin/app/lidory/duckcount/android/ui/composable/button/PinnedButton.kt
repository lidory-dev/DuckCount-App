package app.lidory.duckcount.android.ui.composable.button

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * The room every form leaves under its button, above the keyboard while it is open and above the
 * navigation bar otherwise, whether the button is pinned at the bottom of the window, see
 * [isButtonPinned], or scrolls with the form: never right on either.
 */
val ButtonBottomPadding: Dp = Padding.large

/**
 * The height the window has to keep above the keyboard, or above the navigation bar while the
 * keyboard is closed, for a screen to keep its button pinned there, see [isButtonPinned]: room for
 * the top bar, the button and the field being filled.
 */
val MinHeightForPinnedButton: Dp = 320.dp

/**
 * Whether a screen whose list can hold more than fits keeps its button pinned at the bottom of the
 * window, pushed up by the keyboard as it opens, so that the button stays in reach as on every
 * form: while the window keeps [MinHeightForPinnedButton] above the keyboard, see
 * [isRoomForPinnedButton]. With less, as on a phone in landscape with the keyboard open, a pinned
 * button would leave no room for the field being filled. A form's button then goes at the end of
 * the form instead, reached by scrolling, as on the forms that scroll theirs with them, and a
 * button floating over a list, the dashboard's add button, stays where it is with the keyboard
 * closed, behind the keyboard until it closes.
 */
@Composable
fun isButtonPinned(): Boolean {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    // The keyboard's inset holds the navigation bar's while it is open.
    val below = WindowInsets.ime.union(WindowInsets.navigationBars)

    // Derived, so that the screen recomposes once the answer changes, at most once as the keyboard
    // opens or closes, rather than on every frame its inset moves by.
    return remember(density, windowInfo, below) {
        derivedStateOf {
            val heightAboveKeyboard = windowInfo.containerSize.height - below.getBottom(density)

            isRoomForPinnedButton(heightAboveKeyboard = with(density) { heightAboveKeyboard.toDp() })
        }
    }.value
}

/**
 * Whether [heightAboveKeyboard], what the window keeps above the keyboard, or above the navigation
 * bar while the keyboard is closed, leaves room for a pinned button, see [isButtonPinned].
 */
fun isRoomForPinnedButton(heightAboveKeyboard: Dp): Boolean = heightAboveKeyboard >= MinHeightForPinnedButton
