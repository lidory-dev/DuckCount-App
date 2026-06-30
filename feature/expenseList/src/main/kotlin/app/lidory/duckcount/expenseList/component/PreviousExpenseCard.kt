package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.extensions.formatDateTiny
import app.lidory.duckcount.expenseList.model.PreviousBeneficiary
import kotlinx.datetime.LocalDateTime
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun PreviousExpenseCard(
    modifier: Modifier = Modifier,
    modifiedAt: String,
    modifiedUserFullName: String,
    previousAmount: String,
    previousBeneficiaries: List<PreviousBeneficiary>,
    previousCreditorFullName: String,
    previousDescription: String,
    previousTitle: String,
    currencySymbol: String,
) {
    Card(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(Padding.medium),
        ) {
            Text(
                text = previousTitle,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = previousDescription.ifBlank { stringResource(androidUiR.string.no_description) },
                style = MaterialTheme.typography.bodyMedium,
            )


            Spacer(modifier = Modifier.padding(Padding.small))

            Text(
                text = stringResource(R.string.paid_by),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = previousCreditorFullName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    modifier = Modifier.wrapContentWidth(),
                    maxLines = 1,
                    text = "$previousAmount $currencySymbol",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = stringResource(R.string.beneficiaries),
                style = MaterialTheme.typography.titleMedium,
            )

            previousBeneficiaries.forEach {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = it.fullName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // TODO: The currency symbol might be before the amount.
                    Text(
                        text = "${it.share} $currencySymbol",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.padding(Padding.small))

            Text(
                text = stringResource(androidUiR.string.modified_by, modifiedUserFullName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )

            val date = LocalDateTime.parse(modifiedAt)

            Text(
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                text = stringResource(
                    R.string.date,
                    date.date.formatDateTiny(),
                    "${date.time.hour}:${date.time.minute}",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}