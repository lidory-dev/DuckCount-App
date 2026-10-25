package networking

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/**
 * How long a `429` whose `Retry-After` does not read as the server writes it is taken to hold
 * for: a minute, the shortest window the server has. A resend that comes too early is refused
 * again, with the headers that say when.
 */
private const val UNREAD_RETRY_AFTER_SECONDS = 60L

/**
 * When the limit a `429` came from lets a request through again: its `Retry-After`, in the whole
 * seconds the server writes it in, or [UNREAD_RETRY_AFTER_SECONDS] when it does not read so.
 */
internal fun Headers.retryAfterSeconds(): Long =
    get(HttpHeaders.RetryAfter)?.toLongOrNull() ?: UNREAD_RETRY_AFTER_SECONDS
