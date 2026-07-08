package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.constant.Elevation
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.model.ExpenseStatus

@Composable
fun ExpenseCard(
    modifier: Modifier = Modifier,
    title: String,
    creditorName: String,
    debtorName: String = "",
    totalAmount: String,
    currencySymbol: String,
    personalShare: String?,
    role: ExpenseStatus,
    isActive: Boolean,
    isRefund: Boolean,
) {
    ElevatedCard(
        modifier = modifier
            .alpha(if (isActive) 1f else 0.75f)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = Elevation.small,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = Padding.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(all = Padding.small),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge.copy(
                        textDecoration = if (isActive) TextDecoration.None else TextDecoration.LineThrough,
                    ),
                )

                Text(
                    text = if (isRefund) stringResource(
                        id = R.string.debtor_refund_creditor,
                        creditorName,
                        debtorName,
                    ) else stringResource(id = R.string.paid_by_creditor, creditorName),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium.copy(
                        textDecoration = if (isActive) TextDecoration.None else TextDecoration.LineThrough,
                    ),
                    fontStyle = FontStyle.Italic,
                )
            }

            Spacer(
                modifier = Modifier.weight(.1f),
            )

            val screenWidth = LocalWindowInfo.current.containerDpSize.width
            ExpenseAmount(
                modifier = Modifier
                    .widthIn(max = screenWidth / 3),
                totalAmount = totalAmount,
                role = role,
                isActive = isActive,
                personalShare = if (isRefund) null else personalShare,
                currencySymbol = currencySymbol,
            )
        }
    }
}
