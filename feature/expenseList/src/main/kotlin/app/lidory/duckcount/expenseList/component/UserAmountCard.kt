package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.model.Currency

@Composable
fun UserAmountCard(
    modifier: Modifier = Modifier,
    userFullName: String,
    amount: String,
    currency: Currency,
) {
    Card(
        shape = RoundedCornerShape(size = Padding.large),
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = Padding.medium)
                .height(TextFieldDefaults.MinHeight),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // TODO: Replace with ProfilePicture when merged
                var boxSize by remember { mutableStateOf(IntSize.Zero) }
                Card(
                    modifier = Modifier
                        .padding(Padding.medium)
                        .aspectRatio(1f),
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Blue,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size -> boxSize = size },
                        contentAlignment = Alignment.Center,
                    ) {
                        val textSize = with(LocalDensity.current) {
                            (boxSize.width * 0.5f).toSp()
                        }

                        Text(
                            fontSize = textSize,
                            text = userFullName.take(1),
                        )
                    }
                }

                Text(
                    text = userFullName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // TODO: The currency symbol might be before the amount.
            Text(text = "$amount${currency.symbol}")
        }
    }
}
