package component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R

@Composable
fun ExpenseErrorCard(
    error: ExpenseError?,
    modifier: Modifier = Modifier,
) {
    when (error) {
        ExpenseError.SHARES_BELOW_TOTAL_AMOUNT -> {
            ErrorCard(
                text = stringResource(R.string.shares_below_total_amount),
                modifier = modifier,
            )
        }

        ExpenseError.SHARES_EXCEED_TOTAL_AMOUNT -> {
            ErrorCard(
                text = stringResource(R.string.shares_exceed_total_amount),
                modifier = modifier,
            )
        }

        ExpenseError.NON_POSITIVE_SHARE -> {
            ErrorCard(
                text = stringResource(R.string.non_positive_amount_share),
                modifier = modifier,
            )
        }

        else -> Unit
    }
}

@Composable
fun ErrorCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(size = Padding.large),
        colors = CardDefaults.cardColors().copy(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier = Modifier.padding(start = Padding.large),
                painter = painterResource(R.drawable.ic_exclamation),
                contentDescription = stringResource(R.string.exclamation_mark),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )

            Text(
                modifier = Modifier.padding(vertical = Padding.large, horizontal = Padding.large),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                text = text,
            )
        }
    }
}