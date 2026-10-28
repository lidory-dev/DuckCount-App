package networking

/**
 * The page of [items] that [cursor] starts, [perPage] of them at most, as the server pages a
 * listing, see [readWholeListing]: here the cursor is where the next page starts among [items],
 * and the first page is asked for with none.
 */
internal fun <T> pageOf(items: List<T>, cursor: String?, perPage: Int): ListingPage<T> {
    val from = cursor?.toInt() ?: 0
    val to = if (items.size - from <= perPage) items.size else from + perPage

    return ListingPage(items = items.subList(from, to), nextCursor = to.takeIf { it < items.size }?.toString())
}
