package app.lidory.duckcount.android.ui.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

    @Test
    fun `an edit within the limit keeps its cursor`() {
        val edited = TextFieldValue(text = "Jean-", selection = TextRange(5))

        assertEquals(edited, edited.limitedTo(maxLength = 11, previous = TextFieldValue("Jean", TextRange(4))))
    }

    /**
     * The field as it was, cursor and composition included: the cursor the refused edit moved
     * past the hyphen would have the next deletion take the space instead of the "n".
     */
    @Test
    fun `a letter refused in the middle of a full field leaves the cursor where it was`() {
        val previous = TextFieldValue(text = "Jean Dupont", selection = TextRange(4), composition = TextRange(0, 4))
        val edited = TextFieldValue(text = "Jean- Dupont", selection = TextRange(5))

        assertEquals(previous, edited.limitedTo(maxLength = 11, previous = previous))
    }

    @Test
    fun `a cut paste leaves the cursor after what is kept of it`() {
        val previous = TextFieldValue(text = "Ana Lima", selection = TextRange(4))
        val edited = TextFieldValue(text = "Ana Maria Lima", selection = TextRange(10))

        assertEquals(
            TextFieldValue(text = "Ana MaLima", selection = TextRange(6)),
            edited.limitedTo(maxLength = 10, previous = previous),
        )
    }

    /**
     * A text already past the limit can still be shortened: the server holds a name to what 50
     * units of three bytes seal to, which more units of one byte fit in, as another client may
     * have saved.
     */
    @Test
    fun `a deletion from a text past the limit is kept`() {
        val edited = TextFieldValue(text = "Jean Dupont", selection = TextRange(4))

        assertEquals(edited, edited.limitedTo(maxLength = 5, previous = TextFieldValue("Jean- Dupont", TextRange(5))))
    }
}
