package database.data.local.dao

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a search filter of [UserDao] is matched as, see [escapedForLike]. */
class EscapedForLikeTest {

    @Test
    fun `a filter with nothing special in it is kept as it is`() {
        assertEquals("Alice Martin", "Alice Martin".escapedForLike())
        assertEquals("", "".escapedForLike())
    }

    @Test
    fun `a percent sign and an underscore are put after a backslash`() {
        assertEquals("100\\%", "100%".escapedForLike())
        assertEquals("Dan\\_Lee", "Dan_Lee".escapedForLike())
    }

    /** Before the others: it would otherwise escape the backslash put before them. */
    @Test
    fun `a backslash is put after a backslash too`() {
        assertEquals("Eve\\\\Doe", "Eve\\Doe".escapedForLike())
        assertEquals("\\\\\\%", "\\%".escapedForLike())
    }
}
