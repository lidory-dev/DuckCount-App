package component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.constant.Padding

/** Three lines of four, for the twelve groups of a safety number, when four fit on a line. */
private const val MAX_GROUPS_PER_LINE = 4

/**
 * The [safetyNumber] the user reads out against the one on the contact's phone, its twelve
 * groups of five digits laid out in three lines of four, monospaced so that the columns line up
 * for whoever follows along.
 *
 * A group never breaks across two lines, nor runs into the next one, since the user reads them out
 * one by one: when four do not fit with a gap between them, with a large font or display size or
 * on a narrow screen, a line holds as many as fit instead. The groups being all the same width,
 * every line then holds the same number, three, two or one, and each of those divides twelve, so
 * the columns still line up.
 *
 * A screen reader reads it as one, group after group, rather than as twelve numbers to step
 * through.
 */
@Composable
fun SafetyNumberCard(
    safetyNumber: String,
    modifier: Modifier = Modifier,
) {
    // The library separates the groups with single spaces.
    val groups = remember(safetyNumber) { safetyNumber.split(' ') }
    val description = stringResource(R.string.safety_number_description, groups.joinToString(separator = ", "))

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(size = Padding.large),
    ) {
        // A FlowRow measures each group against the whole line and moves it to the next one when
        // it does not fit, where a Row would squeeze the last into whatever width the others leave.
        // It counts only the arrangement's spacing when it does, which SpaceEvenly has none of:
        // four groups that only just fit would then touch, and a line read as twenty digits.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Padding.extraLarge)
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(Padding.large, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Padding.large),
            maxItemsInEachRow = MAX_GROUPS_PER_LINE,
        ) {
            groups.forEach { group ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
