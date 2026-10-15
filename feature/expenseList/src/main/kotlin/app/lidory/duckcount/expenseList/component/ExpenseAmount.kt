package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.expenseList.model.ExpenseStatus

@Composable
fun ExpenseAmount(
    modifier: Modifier = Modifier,
    totalAmount: String,
    currencySymbol: String,
    role: ExpenseStatus,
    personalShare: String? = null,
    isActive: Boolean,

    /** Shown in place of [personalShare], which nobody owes any more. */
    isSettled: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            // TODO: adapt order if the currency has to be before (ex: 20€ / $20)
            text = "$totalAmount $currencySymbol",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge.copy(
                textDecoration = if (isActive) TextDecoration.None else TextDecoration.LineThrough,
            ),
        )

        if (isSettled) {
            // On the card's container, where primary does not read.
            SettledLabel(color = LocalContentColor.current, singleLine = true)
        } else personalShare?.let {
            Text(
                // TODO: adapt order if the currency has to be before (ex: 20€ / $20)
                text = "${role.labelAmount} $personalShare $currencySymbol",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = role.color,
                style = MaterialTheme.typography.titleSmall.copy(
                    textDecoration = if (isActive) TextDecoration.None else TextDecoration.LineThrough,
                ),
            )
        }
    }
}
