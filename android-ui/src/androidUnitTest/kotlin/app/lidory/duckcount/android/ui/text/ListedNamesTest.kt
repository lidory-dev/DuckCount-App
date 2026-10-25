package app.lidory.duckcount.android.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a message names the people it is about, see [listedNames]. */
class ListedNamesTest {

    private val separator = ", "
    private val english = "%1\$s and %2\$s"
    private val french = "%1\$s et %2\$s"

    @Test
    fun `one name is said alone`() {
        assertEquals("Alice", listedNames(names = listOf("Alice"), separator = separator, lastPair = english))
    }

    @Test
    fun `two names are joined by the language's and`() {
        assertEquals("Alice and Bob", listedNames(names = listOf("Alice", "Bob"), separator = separator, lastPair = english))
        assertEquals("Alice et Bob", listedNames(names = listOf("Alice", "Bob"), separator = separator, lastPair = french))
    }

    @Test
    fun `more names are separated until the last two`() {
        assertEquals(
            "Alice, Bob and Carol",
            listedNames(names = listOf("Alice", "Bob", "Carol"), separator = separator, lastPair = english),
        )
    }
}
