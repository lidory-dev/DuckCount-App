package networking

import io.ktor.client.plugins.logging.Logger

/**
 * Ktor client logger for HTTP request/response tracing.
 *
 * The default logger does not reliably surface on Android's Logcat, so each platform
 * supplies one that writes somewhere visible (Logcat on Android, stdout on iOS).
 */
expect val httpLogger: Logger

/**
 * Whether HTTP traffic is logged at all, which only a debug build does.
 *
 * Nothing strips the logging out of a release build, and on Android whatever it writes to
 * Logcat is there for adb, a bug report or anything granted `READ_LOGS` to read, tokens
 * included.
 */
expect val logsHttpTraffic: Boolean
