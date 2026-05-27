package app.lidory.duckcount.expenseList.utils

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun getCurrentTimestamp(): String {
    return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).format(
        LocalDateTime.Formats.ISO,
    )
}