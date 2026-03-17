package component

import android.text.format.DateFormat
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.lidory.duckcount.android.ui.constant.Elevation
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlin.time.Clock

@Composable
fun StickyHeaderElevated(
    modifier: Modifier = Modifier,
    date: LocalDateTime,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.5f to MaterialTheme.colorScheme.background,
                        1f to Color.Transparent,
                    )
                )
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
            FormattedDate(date)
        }
    }
}

@Composable
fun FormattedDate(date: LocalDateTime) {
    val instantDate = date.toInstant(timeZone = TimeZone.currentSystemDefault())

    val dateFormat = if (date.isCurrentYear()) {
        R.string.date_format
    } else {
        R.string.date_format_with_year
    }

    val dateFormatted =
        DateFormat.format(
            stringResource(id = dateFormat),
            instantDate.toEpochMilliseconds(),
        ).toString()

    Text(
        text = dateFormatted,
        modifier = Modifier.padding(all = Padding.medium),
        fontWeight = FontWeight.Bold,
    )
}

fun LocalDateTime.isCurrentYear(): Boolean {
    val currentDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
    return this.year == currentDate.year
}