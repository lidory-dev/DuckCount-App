package app.lidory.duckcount.expenseList.component

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
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R

@Composable
fun ExpenseErrorCard(
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