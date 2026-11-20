package app.lidory.duckcount.expenseList.extensions

import android.icu.text.DisplayContext
import android.icu.text.SimpleDateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.expenseList.R
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import java.util.Date
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The day, "Friday 20 November", and its year when it is not this one, in the device's language.
 * [isStandalone] for a date standing on its own, as a list's day header does, which its language
 * capitalizes if it writes one so: "Vendredi 20 novembre" in French, where Swedish keeps "fredag
 * 20 november". Otherwise as it is written within a sentence: "Remboursée le vendredi 20 novembre".
 */
@OptIn(ExperimentalTime::class)
@Composable
fun LocalDate.formatDate(isStandalone: Boolean = false): String {
    val timeZone = TimeZone.currentSystemDefault()
    val instantDate = this
        .atTime(hour = 0, minute = 0)
        .toInstant(timeZone = timeZone)

    val pattern = stringResource(
        id = if (this.isCurrentYear()) R.string.date_format else R.string.date_format_with_year,
    )
    val locale = Locale.getDefault()

    val format = remember(pattern, locale, timeZone, isStandalone) {
        SimpleDateFormat(pattern, locale).apply {
            // The zone the day's midnight was taken in, for it to be read back as the same day.
            this.timeZone = android.icu.util.TimeZone.getTimeZone(timeZone.id)
            // Whether to capitalize is the language's call, which ICU's data for the stand-alone
            // context makes; a date within a sentence is left as the language writes its names.
            setContext(
                if (isStandalone) {
                    DisplayContext.CAPITALIZATION_FOR_STANDALONE
                } else {
                    DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE
                },
            )
        }
    }

    return format.format(Date(instantDate.toEpochMilliseconds()))
}

/** The time as shown next to a date, the minutes always in two digits: 9:05, 16:00. */
fun LocalTime.formatTime(): String = "$hour:${minute.toString().padStart(2, '0')}"

@OptIn(ExperimentalTime::class)
fun LocalDate.isCurrentYear(): Boolean {
    val currentDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
    return this.year == currentDate.year
}
