package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding
import kotlin.math.absoluteValue

@Composable
fun BalanceGraphItem(
    fullName: String,
    debt: String,
    maxAmount: Double,
    modifier: Modifier = Modifier,
) {
    val elements: List<@Composable RowScope.() -> Unit> = listOf(
        {
            Text(
                modifier = Modifier.weight(1f),
                fullName = fullName,
                debt = debt.toDouble(),
            )
        },
        {
            Bar(
                modifier = Modifier.weight(1f),
                maxAmount = maxAmount,
                amount = debt.toDouble(),
            )
        },
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        debt.toIntOrNull()?.let { debt ->
            if (debt < 0) {
                elements.reversed().forEach { it() }
            } else {
                elements.forEach { it() }
            }
        }
    }
}

@Composable
fun Bar(
    maxAmount: Double,
    amount: Double,
    modifier: Modifier = Modifier,
) {
    val spec = BarSpec.fromAmount(amount = amount)
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = spec.alignment,
    ) {
        val barWidth: Dp =
            ((amount.absoluteValue * this.maxWidth.value) / maxAmount.absoluteValue).toInt().dp
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(40.dp)
                .background(color = spec.color, shape = spec.shape),
        )
        // TODO: Add currency
        Text(
            modifier = Modifier.padding(horizontal = Padding.medium),
            text = "$amount",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Text(
    debt: Double,
    fullName: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(horizontal = Padding.medium),
        contentAlignment = if (debt < 0) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        },
    ) {
        Text(
            text = fullName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

data class BarSpec(
    val color: Color,
    val alignment: Alignment,
    val shape: Shape,
) {
    companion object {
        @Composable
        fun fromAmount(amount: Double): BarSpec =
            if (amount < 0) {
                BarSpec(
                    color = MaterialTheme.colorScheme.error,
                    alignment = Alignment.CenterEnd,
                    shape = RoundedCornerShape(topStart = Padding.medium, bottomStart = Padding.medium),
                )
            } else {
                BarSpec(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    alignment = Alignment.CenterStart,
                    shape = RoundedCornerShape(topEnd = Padding.medium, bottomEnd = Padding.medium),
                )
            }
    }
}