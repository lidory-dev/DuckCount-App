package networking

import io.ktor.client.plugins.logging.Logger

/**
 * Ktor client logger for HTTP request/response tracing.
 *
 * The default logger does not reliably surface on Android's Logcat, so each platform
 * supplies one that writes somewhere visible (Logcat on Android, stdout on iOS).
 */
expect val httpLogger: Logger
