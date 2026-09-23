package util

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The current instant in milliseconds since the epoch.
 *
 * What the tables that only ever compare two instants to each other store, rather than the
 * formatted local timestamps the expense fields carry: those are the user's own account of
 * when something happened, while these are bookkeeping, and a number that sorts and
 * subtracts is both cheaper to compare in SQL and immune to a device changing time zone.
 */
@OptIn(ExperimentalTime::class)
internal fun nowEpochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()
