package app.lidory.duckcount.android.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.R

/**
 * [names], at least one, as a sentence lists them: [separator] between all but the last, and
 * [lastPair], the language's "%1$s and %2$s", joining the others to the last, as in "Alice",
 * "Alice and Bob", "Alice, Bob and Carol".
 */
fun listedNames(names: List<String>, separator: String, lastPair: String): String {
    val others = names.dropLast(1)

    return if (others.isEmpty()) names.single() else lastPair.format(others.joinToString(separator), names.last())
}

/** [names], at least one, as a sentence in the user's language lists them, see [listedNames]. */
@Composable
fun listedNames(names: List<String>): String =
    listedNames(
        names = names,
        separator = stringResource(R.string.names_list_separator),
        lastPair = stringResource(R.string.names_list_last),
    )
