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
import app.lidory.duckcount.android.ui.text.formatAmount
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseStatus

@Composable
fun ExpenseAmount(
    modifier: Modifier = Modifier,
    totalAmount: String,
    currency: Currency,
    role: ExpenseStatus,

    /**
     * What the user gets back of it as its creditor, or owes of it as a beneficiary, as the library
     * writes an amount: shown with a plus or a minus as [role] says.
     */
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
            text = formatAmount(
                amount = totalAmount,
                currencyCode = currency.name,
                symbol = currency.symbol,
                hasSubunits = currency.hasSubunits,
            ),
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
                text = formatAmount(
                    amount = if (role == ExpenseStatus.BENEFICIARY) "-$personalShare" else personalShare,
                    currencyCode = currency.name,
                    symbol = currency.symbol,
                    hasSubunits = currency.hasSubunits,
                    withSign = true,
                ),
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
