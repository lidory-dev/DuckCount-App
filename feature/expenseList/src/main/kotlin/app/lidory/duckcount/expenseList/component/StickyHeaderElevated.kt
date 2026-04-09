package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.lidory.duckcount.android.ui.constant.Elevation
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.extensions.formatDate
import kotlinx.datetime.LocalDate

@Composable
fun StickyHeaderElevated(
    modifier: Modifier = Modifier,
    date: LocalDate,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.5f to MaterialTheme.colorScheme.background,
                        1f to Color.Transparent,
                    ),
                ),
            ),
    ) {
        ElevatedCard(
            modifier = Modifier
                .padding(all = Padding.small)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = Elevation.small,
            ),
        ) {
            Text(
                text = date.formatDate(),
                modifier = Modifier.padding(all = Padding.medium),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
