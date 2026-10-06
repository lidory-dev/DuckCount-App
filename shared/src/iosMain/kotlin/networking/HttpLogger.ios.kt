package networking

import io.ktor.client.plugins.logging.Logger
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

// iOS: route Ktor HTTP logs to stdout (visible in Xcode's console).
actual val httpLogger: Logger = object : Logger {
    override fun log(message: String) {
        println("DuckCountHttp: $message")
    }
}

// The framework is built for the Xcode configuration, so a Debug run logs and a Release one
// does not.
@OptIn(ExperimentalNativeApi::class)
actual val logsHttpTraffic: Boolean = Platform.isDebugBinary
