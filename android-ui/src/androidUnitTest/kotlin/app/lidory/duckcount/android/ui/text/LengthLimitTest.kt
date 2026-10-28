package app.lidory.duckcount.android.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** A field stopping at its limit, see [limitedTo]. */
class LengthLimitTest {

    @Test
    fun `text within the limit is kept whole`() {
        assertEquals("Alice", "Alice".limitedTo(maxLength = 5, previous = "Alic"))
        assertEquals("", "".limitedTo(maxLength = 5, previous = "A"))
    }

    @Test
    fun `a paste into an empty field is cut at the limit`() {
        assertEquals("Alice", "Alice Martin".limitedTo(maxLength = 5, previous = ""))
    }

    @Test
    fun `a letter typed into the middle of a full field is refused`() {
        assertEquals("Jean Dupont", "Jean- Dupont".limitedTo(maxLength = 11, previous = "Jean Dupont"))
    }

    @Test
    fun `a letter typed at the end of a full field is refused`() {
        assertEquals("Jean Dupont", "Jean Dupontt".limitedTo(maxLength = 11, previous = "Jean Dupont"))
    }

    /** Only what the paste inserts is cut: the text on either side of it stays whole. */
    @Test
    fun `a paste into the middle keeps the text around it whole`() {
        assertEquals("Ana MaLima", "Ana Maria Lima".limitedTo(maxLength = 10, previous = "Ana Lima"))
    }

    @Test
    fun `a paste over a selection keeps what fits of what it inserts`() {
        assertEquals("Anna Lee", "Annabelle Lee".limitedTo(maxLength = 8, previous = "Ann Lee"))
    }

    /** "😀" is two UTF-16 units, which the limit counts as the app's other limits do. */
    @Test
    fun `a character of two units counts as two`() {
        assertEquals("Al😀", "Al😀ce".limitedTo(maxLength = 4, previous = ""))
    }

    @Test
    fun `a character whose second half is past the limit is dropped whole`() {
        assertEquals("Ali", "Ali😀".limitedTo(maxLength = 4, previous = "Ali"))
    }

    /**
     * "😀" and "🨀" end in the same unit, so the two texts have a common end starting halfway
     * through a character, which the edit is not cut at.
     */
    @Test
    fun `an edit is told apart on the edges of characters`() {
        assertEquals("x", "x🨀".limitedTo(maxLength = 2, previous = "😀"))
    }
}
