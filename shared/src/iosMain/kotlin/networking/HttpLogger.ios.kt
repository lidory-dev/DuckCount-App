package networking

import io.ktor.client.plugins.logging.Logger

// iOS: route Ktor HTTP logs to stdout (visible in Xcode's console).
actual val httpLogger: Logger = object : Logger {
    override fun log(message: String) {
        println("DuckCountHttp: $message")
    }
}
