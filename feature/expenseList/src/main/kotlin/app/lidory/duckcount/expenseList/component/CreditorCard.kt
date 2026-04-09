package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R

@Composable
fun CreditorCard(
    modifier: Modifier = Modifier,
    fullName: String,
    amount: String,
    currency: String,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // TODO: Replace with Profile Picture from android-ui when merged
        Card(
            modifier = Modifier
                .fillMaxWidth(.2f)
                .padding(Padding.medium)
                .aspectRatio(1f),
            shape = CircleShape,
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
                    text = fullName.take(1),
                )
            }
        }

        Text(text = stringResource(R.string.paid_by, fullName, "$amount $currency"))


    }
}