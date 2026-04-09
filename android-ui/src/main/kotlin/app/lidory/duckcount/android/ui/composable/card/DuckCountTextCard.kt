package app.lidory.duckcount.android.ui.composable.card

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lidory.duckcount.android.ui.constant.Elevation
import app.lidory.duckcount.android.ui.constant.Padding

@Composable
fun DuckCountTextCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(
            defaultElevation = Elevation.small,
        ),
    ) {
        Row(
            modifier = Modifier.padding(all = Padding.large),
        ) {
            Text(
                text = text,
            )
        }
    }
}