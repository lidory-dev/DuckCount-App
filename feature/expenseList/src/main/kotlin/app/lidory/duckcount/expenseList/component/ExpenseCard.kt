package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.constant.Elevation
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R

@Composable
fun ExpenseCard(
    modifier: Modifier = Modifier,
    title: String,
    creditorName: String,
    amount: String,
    isActive: Boolean,
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
                    text = stringResource(id = R.string.paid_by, creditorName, amount),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium.copy(
                        textDecoration = if (isActive) TextDecoration.None else TextDecoration.LineThrough,
                    ),
                    fontStyle = FontStyle.Italic,
                )
            }

            //TODO: Change with beneficiary amount and real data
            val role = ExpenseStatus.BENEFICIARY

            if (role != ExpenseStatus.NEUTRAL) {
                Spacer(
                    modifier = Modifier.weight(.1f),
                )

                val screenWidth = LocalWindowInfo.current.containerDpSize.width
                ExpenseAmount(
                    modifier = Modifier
                        .widthIn(max = screenWidth / 3),
                    amount = amount,
                    role = role,
                    isActive = isActive,
                )
            }
        }
    }
}

@Composable
fun ExpenseAmount(modifier: Modifier = Modifier, amount: String, role: ExpenseStatus, isActive: Boolean) {
    //TODO: Add parameter with real data

    // role is defined temporary on BENEFICIARY waiting the real data
    val amountDisplay = if (role != ExpenseStatus.NEUTRAL) amount else ""
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${role.labelAmount} $amountDisplay",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = role.color,
            style = MaterialTheme.typography.titleLarge.copy(
                textDecoration = if (isActive) TextDecoration.None else TextDecoration.LineThrough,
            ),
        )
    }
}

enum class ExpenseStatus(val labelAmount: String, val color: Color) {
    BENEFICIARY(
        labelAmount = "+",
        color = Color(0xff5ccb5f),
    ),
    CREDITOR(
        labelAmount = "-",
        color = Color(0xffe63946),
    ),
    NEUTRAL(
        labelAmount = "",
        color = Color.LightGray,
    )
}
