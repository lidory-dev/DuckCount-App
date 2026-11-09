package app.lidory.duckcount.android.ui.animation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackDirectionTest {

    private fun directionOf(vararg backStack: String) = BackStackDirection(backStack = backStack.toList())

    @Test
    fun `taking the top screen off is a pop`() {
        val direction = directionOf("settings", "account", "password")

        direction.record(listOf("settings", "account"))

        assertTrue(direction.isPop)
    }

    /** What a second Back pressed during the animation of the first comes to. */
    @Test
    fun `a pop right after a pop is still a pop`() {
        val direction = directionOf("settings", "account", "password")

        direction.record(listOf("settings", "account"))
        direction.record(listOf("settings"))

        assertTrue(direction.isPop)
    }

    @Test
    fun `taking several screens off at once is a pop`() {
        val direction = directionOf("list", "group", "settings")

        direction.record(listOf("list"))

        assertTrue(direction.isPop)
    }

    @Test
    fun `adding a screen is no pop`() {
        val direction = directionOf("settings")

        direction.record(listOf("settings", "account"))

        assertFalse(direction.isPop)
    }

    /** A Back then a tap, the tap's screen opening the way a screen opens. */
    @Test
    fun `adding a screen right after a pop is no pop`() {
        val direction = directionOf("settings", "account")

        direction.record(listOf("settings"))
        direction.record(listOf("settings", "about"))

        assertFalse(direction.isPop)
    }

    @Test
    fun `replacing the top screen is no pop`() {
        val direction = directionOf("list", "form")

        direction.record(listOf("list", "group"))

        assertFalse(direction.isPop)
    }

    /** A shorter back stack that is not the start of the last one, signing out for instance. */
    @Test
    fun `replacing the whole back stack with a shorter one is no pop`() {
        val direction = directionOf("dashboard", "settings")

        direction.record(listOf("onboarding"))

        assertFalse(direction.isPop)
    }

    /** A back stack that did not change comes up whenever its screen recomposes. */
    @Test
    fun `a back stack that did not change keeps the direction it last moved`() {
        val direction = directionOf("settings", "account")

        direction.record(listOf("settings"))
        direction.record(listOf("settings"))

        assertTrue(direction.isPop)
    }
}
