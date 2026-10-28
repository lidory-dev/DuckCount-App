package networking

import kotlinx.coroutines.runBlocking
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals

/** A listing the server pages with a cursor, read whole, see [readWholeListing]. */
class WholeListingTest {

    /** The cursors each page was asked for with, in order. */
    private val asked = mutableListOf<String?>()

    @Test
    fun `every page is read in order each with the cursor the page before handed back`() = runBlocking<Unit> {
        val pages = mapOf(
            null to ListingPage(items = listOf("m1", "m2"), nextCursor = "c1"),
            "c1" to ListingPage(items = listOf("m3"), nextCursor = "c2"),
            "c2" to ListingPage(items = listOf("m4", "m5"), nextCursor = null),
        )

        val read = readWholeListing { cursor ->
            asked += cursor
            Result.Success(pages.getValue(cursor))
        }

        assertEquals(Result.Success(listOf("m1", "m2", "m3", "m4", "m5")), read)
        assertEquals(listOf(null, "c1", "c2"), asked)
    }

    /** An empty queue is one page with nothing on it and no cursor after it. */
    @Test
    fun `a listing of one page is one request`() = runBlocking<Unit> {
        val read = readWholeListing { cursor ->
            asked += cursor
            Result.Success(ListingPage(items = emptyList<String>(), nextCursor = null))
        }

        assertEquals(Result.Success(emptyList()), read)
        assertEquals(listOf<String?>(null), asked)
    }

    /** Part of a listing would have a pass decide on what it did not see. */
    @Test
    fun `a page that fails fails the whole read and nothing after it is asked for`() = runBlocking<Unit> {
        val read = readWholeListing { cursor ->
            asked += cursor

            when (cursor) {
                null -> Result.Success(ListingPage(items = listOf("m1"), nextCursor = "c1"))
                else -> Result.Error(NetworkError.REQUEST_TIMEOUT)
            }
        }

        assertEquals(Result.Error(NetworkError.REQUEST_TIMEOUT), read)
        assertEquals(listOf(null, "c1"), asked)
    }
}
