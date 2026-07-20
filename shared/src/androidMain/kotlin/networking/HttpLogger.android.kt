package networking

import android.util.Log
import io.ktor.client.plugins.logging.Logger

// Writes Ktor HTTP logs to Logcat under the "DuckCountHttp" tag (adb logcat -s DuckCountHttp).
actual val httpLogger: Logger = object : Logger {
    override fun log(message: String) {
        Log.d("DuckCountHttp", message)
    }
}
