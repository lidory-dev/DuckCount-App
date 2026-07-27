package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.extensions.formatDate
import kotlinx.datetime.LocalDateTime
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun ExpensePreviousVersionCard(
    modifier: Modifier = Modifier,
    editorFullName: String,
    modifiedAt: String,
    isFirstEntry: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(Padding.medium),
        ) {
            val date = LocalDateTime.parse(modifiedAt)
            Text(
                text = stringResource(
                    R.string.date,
                    date.date.formatDate(),
                    "${date.time.hour}:${date.time.minute}",
                ),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            val textRes = if (isFirstEntry) R.string.created_by else androidUiR.string.modified_by
            Text(
                text = stringResource(textRes, editorFullName),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
