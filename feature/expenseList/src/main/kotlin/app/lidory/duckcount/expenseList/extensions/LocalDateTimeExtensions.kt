package app.lidory.duckcount.expenseList.extensions

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.expenseList.R
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun LocalDate.formatDate(): String {
    val instantDate = this
        .atTime(hour = 0, minute = 0)
        .toInstant(timeZone = TimeZone.currentSystemDefault())

    val dateFormat = if (this.isCurrentYear()) {
        R.string.date_format
    } else {
        R.string.date_format_with_year
    }

    val dateFormatted =
        DateFormat.format(
            stringResource(id = dateFormat),
            instantDate.toEpochMilliseconds(),
        ).toString()

    return dateFormatted
}

/** The time as shown next to a date, the minutes always in two digits: 9:05, 16:00. */
fun LocalTime.formatTime(): String = "$hour:${minute.toString().padStart(2, '0')}"

@OptIn(ExperimentalTime::class)
fun LocalDate.isCurrentYear(): Boolean {
    val currentDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
    return this.year == currentDate.year
}