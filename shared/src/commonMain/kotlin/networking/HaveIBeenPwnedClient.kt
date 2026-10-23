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
import util.map

/**
 * Hard cap on the breach check, which registration and a password change wait on before going
 * ahead without it.
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
     * How many times [password] turns up in the breaches Have I Been Pwned knows of, none for a
     * password it does not know, or why it could not be asked within [REQUEST_TIMEOUT_MILLIS].
     *
     * Only the first five characters of the password's SHA-1 leave the device: the range API
     * answers with every breached hash starting with them, which this looks the rest up in.
     */
    suspend fun passwordLeakCount(password: String): Result<Int, NetworkError> {
        val hash = sha1(password.encodeToByteArray()).toUppercaseHex()

        return passwordRange(hashPrefix = hash.take(5)).map { range ->
            leakCount(range = range, hashSuffix = hash.drop(5))
        }
    }

    /**
     * The breached passwords whose SHA-1 starts with [hashPrefix], as the range API lists them,
     * or why they could not be had within [REQUEST_TIMEOUT_MILLIS].
     */
    private suspend fun passwordRange(hashPrefix: String): Result<String, NetworkError> =
        withinTimeout(timeoutMillis = REQUEST_TIMEOUT_MILLIS) {
            get<String>(
                route = HaveIBeenPwnedRoutes.Password.CHECK,
                pathParams = mapOf("sha1FiveFirstCharacters" to hashPrefix),
            )
        }
}

/**
 * How many times the password whose SHA-1 ends with [hashSuffix] was breached, as [range], the
 * range API's answer for the rest of its hash, says: one line of `SUFFIX:COUNT` for each breached
 * hash, and none for a password it does not know.
 */
internal fun leakCount(range: String, hashSuffix: String): Int =
    range.lineSequence()
        .firstOrNull { it.startsWith(prefix = hashSuffix, ignoreCase = true) }
        ?.substringAfter(delimiter = ":")
        ?.trim()
        ?.toIntOrNull()
        ?: 0

/** These bytes as hexadecimal, two uppercase digits each, as the range API spells a hash. */
internal fun ByteArray.toUppercaseHex(): String =
    joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0') }
        .uppercase()

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
