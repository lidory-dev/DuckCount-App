package networking

import util.NetworkError
import util.Result

/** The query parameter a paged listing carries on from where the page before ended with. */
internal const val CURSOR_QUERY = "cursor"

/** One page of a listing the server pages with a cursor, see [readWholeListing]. */
internal class ListingPage<out T>(
    /** What the page lists, in the listing's order. */
    val items: List<T>,

    /** Where the next page starts, the `next_cursor` the server answered, or null on the last page. */
    val nextCursor: String?,
)

/**
 * Reads a listing the server pages with a cursor whole, a page at a time and in order: the first
 * page, which [readPage] is asked for with no cursor, then each with the cursor the page before
 * it handed back, until one hands back none. Or returns the first failure, and nothing of the
 * pages read before it.
 *
 * Both listings of messages waiting for the user are read this way, a group's and the direct
 * ones, by passes that decide on what is waiting as a whole before they acknowledge any of it: the
 * group sync joins from the last Welcome a group's creator wrote, which can be on any page, and
 * the direct one leaves a sender alone for the rest of the pass from the first of their messages
 * it has to read again, whichever page the next one is on. Part of a listing would have them
 * decide on what they did not see.
 *
 * The server pages on when it stored each message and never on how many come before, so a
 * message acknowledged meanwhile moves nothing, and none is listed twice or skipped. One stored
 * behind a page already read, its writer having taken a moment to commit it, is listed from the
 * next pass, which starts from the first page again: no cursor is kept from one pass to the next.
 */
internal suspend fun <T> readWholeListing(
    readPage: suspend (cursor: String?) -> Result<ListingPage<T>, NetworkError>,
): Result<List<T>, NetworkError> {
    val items = mutableListOf<T>()
    var cursor: String? = null

    do {
        val page = when (val read = readPage(cursor)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        items += page.items
        cursor = page.nextCursor
    } while (cursor != null)

    return Result.Success(items)
}
