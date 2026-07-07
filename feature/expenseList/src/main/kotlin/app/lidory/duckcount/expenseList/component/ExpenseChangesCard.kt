package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R

@Composable
fun ExpenseChangesCard(
    modifier: Modifier = Modifier,
    fieldChanged: List<String>,
    modifierUserFullName: String,
    modifiedAt: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(Padding.medium),
        ) {
            val fullText = "$modifierUserFullName changed"
            val userChangedText = buildAnnotatedString {
                append(fullText)

                addStyle(
                    style = SpanStyle(fontWeight = FontWeight.Bold),
                    start = 0,
                    end = modifierUserFullName.length,
                )

                fieldChanged.forEachIndexed { index, field ->
                    when (index) {
                        0 -> append(stringResource(R.string.the_with_white_spaces))
                        fieldChanged.size - 1 -> append(stringResource(R.string.and_the_with_white_spaces))
                        else -> append(stringResource(R.string.the_with_coma))
                    }
                    val start = this.length
                    append(field)
                    addStyle(
                        style = SpanStyle(fontWeight = FontWeight.Bold),
                        start = start,
                        end = this.length,
                    )
                }
            }

            Text(
                text = userChangedText,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.padding(Padding.small))

            Text(
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                text = modifiedAt,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}