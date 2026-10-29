package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.groupList.model.GroupInformationVersion
import java.text.DateFormat
import java.util.Date
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * One version of a group's title and description, as its history shows it: its title, its
 * description when it has one, and when the group was created as it, or edited to it. The version
 * the group is at now is marked as such, see [GroupInformationVersion.isCurrent].
 */
@Composable
fun GroupVersionCard(
    version: GroupInformationVersion,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(Padding.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = version.title,
                    style = MaterialTheme.typography.titleMedium,
                )

                if (version.isCurrent) {
                    Spacer(modifier = Modifier.width(Padding.medium))

                    CurrentVersionLabel()
                }
            }

            if (version.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(Padding.small))

                Text(
                    text = version.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(Padding.medium))

            val madeOn = if (version.isCreation) {
                R.string.group_history_created_on
            } else {
                R.string.group_history_edited_on
            }

            Text(
                text = stringResource(madeOn, revisedAtText(revisedAt = version.revisedAt)),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Marks the version the group is at now, see [GroupInformationVersion.isCurrent]. */
@Composable
private fun CurrentVersionLabel() {
    Surface(
        shape = RoundedCornerShape(size = Padding.medium),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = Padding.medium, vertical = Padding.small),
            text = stringResource(R.string.group_history_current),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * When [revisedAt], in milliseconds since the epoch, was in the device's time zone, its date and
 * its time each told as the device's locale tells them, joined as the app joins a date and a time,
 * see [androidUiR.string.date].
 */
@Composable
private fun revisedAtText(revisedAt: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val date = Date(revisedAt)

    return stringResource(
        androidUiR.string.date,
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date),
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(date),
    )
}
