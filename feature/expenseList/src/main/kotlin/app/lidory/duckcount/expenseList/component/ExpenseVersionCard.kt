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
import app.lidory.duckcount.expenseList.extensions.formatTime
import app.lidory.duckcount.expenseList.model.VersionChange
import app.lidory.duckcount.expenseList.model.authorLineRes
import kotlinx.datetime.LocalDateTime

@Composable
fun ExpenseVersionCard(
    modifier: Modifier = Modifier,

    /** Null for somebody this device holds no profile for. */
    authorFullName: String?,

    /** Null when it is not known. */
    madeAt: LocalDateTime?,

    change: VersionChange,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(Padding.medium),
        ) {
            madeAt?.let { date ->
                Text(
                    text = stringResource(
                        R.string.date,
                        date.date.formatDate(),
                        date.time.formatTime(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = stringResource(change.authorLineRes(), authorFullName ?: stringResource(R.string.unknown_person)),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
