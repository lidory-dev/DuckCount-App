package app.lidory.duckcount.android.ui.text

import java.text.Collator
import java.util.Locale

/**
 * Names in the order the language of [locale] sorts them: by their letters first, accents and
 * case then telling apart names that differ by nothing else, so that Émile comes among the E's
 * rather than after Zoé, and alice before Bruno.
 */
fun nameOrder(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale)

    return Comparator { first, second -> collator.compare(first, second) }
}

/**
 * These people as every list of people in the app orders them: the user, [selfUuid], first, then
 * everybody else by name, [nameOf], see [nameOrder], and last those this device has no name for.
 * Two people of the same name are told apart by [uuidOf], so that they keep their places each time
 * the list is read again.
 */
fun <T> List<T>.sortedByName(
    selfUuid: String?,
    uuidOf: (T) -> String,
    nameOf: (T) -> String?,
    locale: Locale = Locale.getDefault(),
): List<T> =
    sortedWith(
        compareBy<T> { uuidOf(it) != selfUuid }
            .thenBy { nameOf(it) == null }
            .thenBy(nameOrder(locale)) { nameOf(it).orEmpty() }
            .thenBy { uuidOf(it) },
    )
