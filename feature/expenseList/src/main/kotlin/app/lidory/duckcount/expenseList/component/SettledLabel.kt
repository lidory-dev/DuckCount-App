package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.extensions.formatDate
import kotlinx.datetime.LocalDateTime
import app.lidory.duckcount.android.ui.R as androidUiR

/** Says that the expense was marked as paid back, and on which day when [settledAt] is given. */
@Composable
fun SettledLabel(
    modifier: Modifier = Modifier,
    settledAt: LocalDateTime? = null,
    style: TextStyle = MaterialTheme.typography.titleSmall,

    /** Primary reads on a surface; on a container of another color, pass its content color. */
    color: Color = MaterialTheme.colorScheme.primary,

    /** One line, cut short if need be, where the label has a narrow column of its own. */
    singleLine: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            modifier = Modifier.size(18.dp),
            painter = painterResource(androidUiR.drawable.ic_checked_circle),
            contentDescription = null,
            tint = color,
        )

        Text(
            text = settledAt?.let { stringResource(R.string.expense_settled_on, it.date.formatDate()) }
                ?: stringResource(R.string.expense_settled),
            style = style,
            color = color,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}
