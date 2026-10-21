package database.data.remote

import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a `429` from `POST /messages` is read as, see [toRateLimited]. */
class RateLimitedHeadersTest {

    @Test
    fun `a refusal for one recipient is read with when it reopens`() {
        val headers = headersOf(
            "Retry-After" to listOf("17"),
            "X-Rate-Limit-Scope" to listOf("recipient"),
        )

        assertEquals(
            SendDirectMessageError.RateLimited(retryAfterSeconds = 17, scope = MessageLimitScope.RECIPIENT),
            headers.toRateLimited(),
        )
    }

    /** The day's window, which can be most of a day away. */
    @Test
    fun `a refusal for everything the user sends is read with when it reopens`() {
        val headers = headersOf(
            "Retry-After" to listOf("86400"),
            "X-Rate-Limit-Scope" to listOf("sender"),
        )

        assertEquals(
            SendDirectMessageError.RateLimited(retryAfterSeconds = 86_400, scope = MessageLimitScope.SENDER),
            headers.toRateLimited(),
        )
    }

    /** The server writes its header names in lower case, which names them all the same. */
    @Test
    fun `the headers are read whatever their case`() {
        val headers = headersOf(
            "retry-after" to listOf("5"),
            "x-rate-limit-scope" to listOf("recipient"),
        )

        assertEquals(
            SendDirectMessageError.RateLimited(retryAfterSeconds = 5, scope = MessageLimitScope.RECIPIENT),
            headers.toRateLimited(),
        )
    }

    /** Held back the most, for the shortest window the server has. */
    @Test
    fun `headers that do not read as the server writes them are taken for the sender's limit a minute away`() {
        val sendersMinute = SendDirectMessageError.RateLimited(retryAfterSeconds = 60, scope = MessageLimitScope.SENDER)

        assertEquals(sendersMinute, headersOf().toRateLimited())

        assertEquals(
            sendersMinute,
            headersOf(
                "Retry-After" to listOf("Wed, 21 Oct 2026 07:28:00 GMT"),
                "X-Rate-Limit-Scope" to listOf("everybody"),
            ).toRateLimited(),
        )
    }
}
