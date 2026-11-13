package expense

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Now, as an expense is dated: an ISO 8601 date and time in the device's time zone, written by
 * Kotlin's own formatter, as Android's `getCurrentTimestamp` writes it.
 *
 * Built here rather than in Swift because every device refuses to open an expense whose timestamp
 * is not exactly what this formatter writes, see `isCanonicalTimestamp`: it drops the trailing zeros
 * of the fraction of a second, ".12" for 120 ms and none at all for 0, where a fixed
 * `".SSS"` would write ".120" and leave one expense in ten unreadable.
 */
@OptIn(ExperimentalTime::class)
fun currentTimestamp(): String =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).format(LocalDateTime.Formats.ISO)
