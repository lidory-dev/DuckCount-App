package networking

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import util.NetworkError
import util.Result

/**
 * Hard cap on the breach check, which registration waits on before going ahead without it.
 */
private const val REQUEST_TIMEOUT_MILLIS = 2_000L

/**
 * Not logged, in any build: the URL of each lookup carries the start of the password's hash.
 */
class HaveIBeenPwnedClient : Client() {
    override val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
        install(ContentNegotiation) {
            json(
                json = Json {
                    ignoreUnknownKeys = true
                },
            )
        }
    }

    /**
     * The breached passwords whose SHA-1 starts with [hashPrefix], as the range API lists them,
     * or why they could not be had within [REQUEST_TIMEOUT_MILLIS].
     */
    suspend fun passwordRange(hashPrefix: String): Result<String, NetworkError> =
        withinTimeout(timeoutMillis = REQUEST_TIMEOUT_MILLIS) {
            get<String>(
                route = HaveIBeenPwnedRoutes.Password.CHECK,
                pathParams = mapOf("sha1FiveFirstCharacters" to hashPrefix),
            )
        }
}

/**
 * What [request] answers, or [NetworkError.REQUEST_TIMEOUT] once [timeoutMillis] have passed,
 * even when [request] does not stop on being cancelled.
 *
 * The request's own timeout is not enough: it gives up by cancelling the request, and the host's
 * name is looked up by a blocking call that cancelling does not interrupt, so on a network whose
 * DNS does not answer the request would only end once the system gave up, about five seconds
 * later on Android. So [request] runs on its own, and is cancelled, to finish in the background,
 * rather than waited for.
 */
internal suspend fun <T> withinTimeout(
    timeoutMillis: Long,
    request: suspend () -> Result<T, NetworkError>,
): Result<T, NetworkError> {
    val running = CoroutineScope(Dispatchers.IO).async { request() }

    return try {
        withTimeoutOrNull(timeoutMillis) { running.await() } ?: Result.Error(NetworkError.REQUEST_TIMEOUT)
    } finally {
        running.cancel()
    }
}
