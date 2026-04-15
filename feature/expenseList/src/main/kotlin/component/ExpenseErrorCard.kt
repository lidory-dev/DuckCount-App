package component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R

@Composable
fun ExpenseErrorCard(
    error: ExpenseError?,
) {
    if (error != null) {
        if (error == ExpenseError.SHARES_BELOW_TOTAL_AMOUNT) {
            ErrorCard(text = stringResource(R.string.shares_below_total_amount))
        }
        if (error == ExpenseError.SHARES_EXCEED_TOTAL_AMOUNT) {
            ErrorCard(text = stringResource(R.string.shares_below_total_amount))
        }
    }
}

@Composable
fun ErrorCard(
    text: String,
) {
    ElevatedCard(
        modifier = Modifier
            .padding(vertical = Padding.small, horizontal = Padding.large),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = Padding.large),
            // TODO: Use themed color
            color = Color.Red,
            fontSize = 10.sp,
            text = text,
        )
    }
}