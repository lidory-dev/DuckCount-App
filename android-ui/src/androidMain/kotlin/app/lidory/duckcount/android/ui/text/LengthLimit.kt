package app.lidory.duckcount.android.ui.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * [this], a field's value right after an edit of [previous], its text held to [maxLength] as
 * [String.limitedTo] holds it, and its cursor where that leaves it: [previous] whole, cursor and
 * all, when nothing the edit inserted fits, as though the key had not been pressed, and right
 * after what is kept of the insertion when only part of it does.
 *
 * The text alone would not do: the field would keep the cursor the edit moved, one place past
 * the letter it refused, and the next deletion would take the letter after the one meant.
 */
internal fun TextFieldValue.limitedTo(maxLength: Int, previous: TextFieldValue): TextFieldValue {
    val limited = text.limitedTo(maxLength = maxLength, previous = previous.text)

    return when (limited) {
        text -> this
        previous.text -> previous
        // The edit leaves the cursor after what it inserted, of which only the end is dropped.
        else -> TextFieldValue(
            text = limited,
            selection = TextRange((selection.end - (text.length - limited.length)).coerceIn(0, limited.length)),
        )
    }
}

/**
 * [this], a field's text right after an edit of [previous], held to at most [maxLength] UTF-16
 * units, the unit every text limit of the app is counted in, for a field that stops at its limit:
 * of what an edit inserts past it, only as much as fits is kept, from its start, and the rest of
 * the text stays as it was. A letter typed into the middle of a full field is refused, rather than
 * pushing the field's last letter out, and a paste keeps only its first part.
 *
 * The edit is told from the two texts alone, all a field reports being the text it now holds: it
 * replaced what lies between their common start and their common end.
 *
 * Never between the two halves of a surrogate pair, which would leave half a character: a
 * character whose second half does not fit is dropped whole.
 */
internal fun String.limitedTo(maxLength: Int, previous: String): String {
    if (length <= maxLength) return this

    val shorter = minOf(length, previous.length)

    var start = 0
    while (start < shorter && this[start] == previous[start]) start++

    var end = 0
    while (end < shorter - start && this[length - 1 - end] == previous[previous.length - 1 - end]) end++

    // Both on the edge of a character, so that what the edit inserted holds whole characters only.
    if (start > 0 && this[start - 1].isHighSurrogate()) start--
    if (end > 0 && this[length - end].isLowSurrogate()) end--

    val inserted = substring(start, length - end)

    return substring(0, start) + inserted.cutTo(maxLength = maxLength - start - end) + substring(length - end)
}

/**
 * [this] cut down to at most [maxLength] units, nothing when that is none, and never between the
 * two halves of a surrogate pair.
 */
private fun String.cutTo(maxLength: Int): String {
    if (length <= maxLength) return this

    val end = maxLength.coerceAtLeast(0)

    return substring(0, if (end > 0 && this[end - 1].isHighSurrogate()) end - 1 else end)
}
