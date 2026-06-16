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
import java.math.BigDecimal

@Composable
fun BalanceGraphItem(
    fullName: String,
    debt: BigDecimal,
    maxAmount: BigDecimal,
    isAppInDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val elements: List<@Composable RowScope.() -> Unit> = listOf(
        {
            Text(
                modifier = Modifier.weight(1f),
                fullName = fullName,
                debt = debt,
            )
        },
        {
            Bar(
                modifier = Modifier.weight(1f),
                maxAmount = maxAmount,
                amount = debt,
                isAppInDarkTheme = isAppInDarkTheme,
            )
        },
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (debt < BigDecimal.ZERO) {
            elements.reversed().forEach { it() }
        } else {
            elements.forEach { it() }
        }

    }
}

@Composable
fun Bar(
    maxAmount: BigDecimal,
    amount: BigDecimal,
    isAppInDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = BarSpec.fromAmount(amount = amount, isAppInDarkTheme = isAppInDarkTheme)

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = spec.alignment,
    ) {
        val barWidth: Dp =
            ((amount.abs() * this.maxWidth.value.toBigDecimal()) / maxAmount.abs()).toInt().dp

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
    debt: BigDecimal,
    fullName: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(horizontal = Padding.medium),
        contentAlignment = if (debt < BigDecimal.ZERO) {
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
        fun fromAmount(amount: BigDecimal, isAppInDarkTheme: Boolean): BarSpec =
            if (amount < BigDecimal.ZERO) {
                BarSpec(
                    color = if (isAppInDarkTheme) Color(0xFF890401) else Color(0xFFFBA5A2),
                    alignment = Alignment.CenterEnd,
                    shape = RoundedCornerShape(topStart = Padding.medium, bottomStart = Padding.medium),
                )
            } else {
                BarSpec(
                    color = if (isAppInDarkTheme) Color(0xFF0A4401) else Color(0xFFAECCA2),
                    alignment = Alignment.CenterStart,
                    shape = RoundedCornerShape(topEnd = Padding.medium, bottomEnd = Padding.medium),
                )
            }
    }
}
