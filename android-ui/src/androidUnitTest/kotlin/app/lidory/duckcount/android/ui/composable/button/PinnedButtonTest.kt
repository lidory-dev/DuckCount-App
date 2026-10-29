package app.lidory.duckcount.android.ui.composable.button

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a form keeps its button pinned above the keyboard, see [isRoomForPinnedButton]. */
class PinnedButtonTest {

    /** A phone in portrait, its keyboard open: about 580dp of a 914dp window above it. */
    @Test
    fun `a window tall enough above the keyboard keeps the button pinned`() {
        assertTrue(isRoomForPinnedButton(heightAboveKeyboard = 580.dp))
        assertTrue(isRoomForPinnedButton(heightAboveKeyboard = MinHeightForPinnedButton))
    }

    /** A phone in landscape, its keyboard open: about 150dp of a 411dp window above it. */
    @Test
    fun `a window too short above the keyboard scrolls the button with the form`() {
        assertFalse(isRoomForPinnedButton(heightAboveKeyboard = 150.dp))
        assertFalse(isRoomForPinnedButton(heightAboveKeyboard = MinHeightForPinnedButton - 1.dp))
    }
}
